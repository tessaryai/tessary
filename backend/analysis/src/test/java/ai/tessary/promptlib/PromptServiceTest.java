// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.promptlib.PromptService.CreatePromptCommand;
import ai.tessary.promptlib.PromptService.CreateVersionCommand;
import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.LabelMode;
import ai.tessary.promptlib.PromptService.PromptListQuery;
import ai.tessary.promptlib.PromptService.ResolvedVersion;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.PrincipalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * The version, label and delete rules over a mocked repository. The bugs: a version number read before the prompt
 * lock (two writers both take N+1), a unique violation surfacing as a 500, {@code latest} stored or read from a row,
 * {@code REPLACE} leaving a label behind or {@code ADD} removing one, and another project's prompt id reaching a write.
 */
@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    private static final String PID = "proj-1";
    private static final String ID = "prompt-1";
    private static final String BY = "user-1";
    private static final Instant NOW = Instant.parse("2026-10-18T09:12:44.118Z");
    private static final PromptRow TEXT_PROMPT =
            new PromptRow(ID, PID, "p", PromptType.TEXT, List.of(), 1, BY, NOW, NOW);

    @Mock
    PromptRepository repo;

    @Mock
    PrincipalRepository principals;

    private PromptService service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new PromptService(repo, principals, new PromptFetchCache(new PromptCacheProperties(), clock), clock);
    }

    private static Principal person(String id, @Nullable String displayName, @Nullable String email) {
        return new Principal(id, null, email, displayName, null, Principal.Kind.HUMAN, null, "{}", "2026-10-01", null);
    }

    /**
     * The bugs: the version list showed the raw principal id as the author, looked each author up one query at a time,
     * or showed a blank name instead of falling back to the email and then to "Unknown".
     */
    @Test
    void authors_labelByNameThenEmailElseUnknown_inOneLookup() {
        Set<String> ids = Set.of("named", "blank-name", "no-contact", "blank-contact", "gone");
        when(principals.findByIds(ids))
                .thenReturn(List.of(
                        person("named", "Ana Lima", "ana@example.com"),
                        person("blank-name", " ", "bo@example.com"),
                        person("no-contact", null, null),
                        person("blank-contact", null, "")));

        PromptService.Authors authors =
                service().authors(List.of("named", "blank-name", "no-contact", "blank-contact", "gone", "named"));

        assertEquals("Ana Lima", authors.of("named"));
        assertEquals("bo@example.com", authors.of("blank-name"));
        assertEquals("Unknown", authors.of("no-contact"));
        assertEquals("Unknown", authors.of("blank-contact"));
        assertEquals("Unknown", authors.of("gone"));
        verify(principals).findByIds(ids);
    }

    private static PromptVersionRow version(int n) {
        return new PromptVersionRow("v" + n, PID, ID, n, "\"body " + n + "\"", "{}", null, BY, CreateVia.UI, NOW);
    }

    private static PromptLabelRow label(String label, int version) {
        return new PromptLabelRow(PID, ID, label, "v" + version, version, BY, NOW);
    }

    private static CreateVersionCommand textVersion(List<String> labels) {
        return new CreateVersionCommand("hello {{name}}", null, labels, null, null);
    }

    @Test
    void createVersion_numbersFromTheClaimedVersion() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.claimVersion(PID, ID)).thenReturn(4);
        when(repo.labelVersion(PID, ID, "staging")).thenReturn(Optional.empty());

        ResolvedVersion v = service().createVersion(PID, ID, BY, CreateVia.API, textVersion(List.of("staging")));

        ArgumentCaptor<PromptVersionRow> row = ArgumentCaptor.forClass(PromptVersionRow.class);
        InOrder order = inOrder(repo);
        order.verify(repo).lockPrompt(PID, ID);
        order.verify(repo).claimVersion(PID, ID);
        order.verify(repo).insertVersion(row.capture());
        order.verify(repo).upsertLabel(PID, ID, "staging", row.getValue().id(), BY, NOW);
        order.verify(repo).touchPrompt(PID, ID, NOW);
        assertEquals(4, row.getValue().version());
        assertEquals("\"hello {{name}}\"", row.getValue().bodyJson());
        assertEquals("{}", row.getValue().configJson());
        assertEquals(CreateVia.API, row.getValue().createdVia());
        assertEquals(List.of("staging", "latest"), v.labels());
        assertTrue(v.isLatest());
    }

    @Test
    void createVersion_duplicateKeyIsConcurrentWrite() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.claimVersion(PID, ID)).thenReturn(2);
        doThrow(new DuplicateKeyException("ux_prompt_version")).when(repo).insertVersion(any());

        assertError(
                PromptsError.CONCURRENT_WRITE,
                () -> service().createVersion(PID, ID, BY, CreateVia.UI, textVersion(List.of())));
    }

    /**
     * A prompt has no version cap. The bugs: a CI job that publishes one version per deploy stops at 1,000, and every
     * create counts the prompt's versions first, a read whose cost grows with the prompt. The skipped count is the
     * outcome, so it is verified.
     */
    @Test
    void createVersion_afterAThousandVersionsCreatesTheNextWithoutCounting() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.claimVersion(PID, ID)).thenReturn(1001);

        ResolvedVersion v = service().createVersion(PID, ID, BY, CreateVia.UI, textVersion(List.of()));

        assertEquals(1001, v.version().version());
        verify(repo, never()).countVersions(any(), any());
    }

    @Test
    void createVersion_typeComesFromThePrompt() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));

        CreateVersionCommand chatBody =
                new CreateVersionCommand(List.of(Map.of("role", "user", "content", "x")), null, List.of(), null, null);
        assertError(PromptsError.INVALID_BODY, () -> service().createVersion(PID, ID, BY, CreateVia.UI, chatBody));
        verify(repo, never()).insertVersion(any());
    }

    /**
     * The 1 MiB request cap is the only bound on a config. The bug: a 70,000-character
     * config, under that cap, is refused by a cap of its own.
     */
    @Test
    void createVersion_configOverTheOldCapIsStored() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.claimVersion(PID, ID)).thenReturn(2);

        CreateVersionCommand big =
                new CreateVersionCommand("x", Map.of("k", "v".repeat(70_000)), List.of(), null, null);
        assertEquals(
                Map.of("k", "v".repeat(70_000)),
                service().createVersion(PID, ID, BY, CreateVia.UI, big).config());
    }

    /**
     * Tags sent with a next version replace the prompt's tags; no tags keeps them. The bugs: the tags are dropped with
     * no error, and the returned version still shows the tags read before the update.
     */
    @Test
    void createVersion_tagsReplaceThePromptsTags_nullKeepsThem() {
        PromptRow tagged = new PromptRow(ID, PID, "p", PromptType.TEXT, List.of("old"), 1, BY, NOW, NOW);
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(tagged));
        when(repo.claimVersion(PID, ID)).thenReturn(2, 3);

        ResolvedVersion replaced = service()
                .createVersion(
                        PID,
                        ID,
                        BY,
                        CreateVia.API,
                        new CreateVersionCommand("x", null, List.of(), null, List.of("b", "a", "b")));
        ResolvedVersion kept = service()
                .createVersion(PID, ID, BY, CreateVia.API, new CreateVersionCommand("x", null, List.of(), null, null));

        verify(repo).updateTags(PID, ID, List.of("b", "a"), NOW);
        assertEquals(List.of("b", "a"), replaced.prompt().tags());
        assertEquals(List.of("old"), kept.prompt().tags());
    }

    /**
     * Bug: the public create committed version 1 and read it back in a second transaction, so a delete between the two
     * turned a committed create into a 404 and the client's retry wrote a duplicate.
     */
    @Test
    void createFirstVersion_returnsWhatItWrote_withoutReadingItBack() {
        CreatePromptCommand cmd = new CreatePromptCommand(
                " p ", PromptType.TEXT, "hi", null, List.of("production"), List.of("t"), "first");

        ResolvedVersion v = service().createFirstVersion(PID, BY, CreateVia.API, cmd);

        assertEquals("p", v.prompt().name());
        assertEquals(1, v.version().version());
        assertEquals(List.of("production", "latest"), v.labels());
        assertTrue(v.isLatest());
        verify(repo, never()).findVersion(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(repo, never()).findById(anyString(), anyString());
    }

    @Test
    void create_duplicateNameIsNameExists_integrityIsInvalidName() {
        CreatePromptCommand cmd = new CreatePromptCommand("p", PromptType.TEXT, "hi", null, List.of(), List.of(), null);

        doThrow(new DuplicateKeyException("ux_prompt_project_name")).when(repo).insertPrompt(any());
        assertError(PromptsError.NAME_EXISTS, () -> service().create(PID, BY, CreateVia.UI, cmd));

        doThrow(new DataIntegrityViolationException("ck_prompt_name"))
                .when(repo)
                .insertPrompt(any());
        assertError(PromptsError.INVALID_NAME, () -> service().create(PID, BY, CreateVia.UI, cmd));
        verify(repo, never()).insertVersion(any());
    }

    @Test
    void setLabels_replaceRemovesAbsentLabels() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 2)).thenReturn(Optional.of(version(2)));
        when(repo.labelsOf(PID, List.of(ID)))
                .thenReturn(List.of(label("dev", 1), label("production", 2), label("staging", 2)));
        when(repo.maxVersion(PID, ID)).thenReturn(3);

        ResolvedVersion v = service().setLabels(PID, ID, 2, BY, Set.of("production"), LabelMode.REPLACE);

        verify(repo, never()).upsertLabel(anyString(), anyString(), eq("production"), anyString(), anyString(), any());
        verify(repo).deleteLabel(PID, ID, "staging");
        verify(repo, never()).deleteLabel(PID, ID, "dev");
        verify(repo).touchPrompt(PID, ID, NOW);
        assertEquals(List.of("production"), v.labels());
        assertFalse(v.isLatest());
    }

    /** Bug: a retried promote rewrote who promoted the version and when, and logged a move from a version to itself. */
    @Test
    void setLabels_aLabelAlreadyOnTheVersionIsLeftAsItIs_andAnIdenticalRetryWritesNothing() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 2)).thenReturn(Optional.of(version(2)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("production", 2)));
        when(repo.maxVersion(PID, ID)).thenReturn(2);

        ResolvedVersion v = service().setLabels(PID, ID, 2, BY, Set.of("production"), LabelMode.ADD);

        verify(repo, never()).upsertLabel(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(repo, never()).touchPrompt(anyString(), anyString(), any());
        assertEquals(List.of("production", "latest"), v.labels());
    }

    @Test
    void setLabels_addKeepsAbsentLabels() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 2)).thenReturn(Optional.of(version(2)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("staging", 2)));
        when(repo.labelVersion(PID, ID, "production")).thenReturn(Optional.of(1));
        when(repo.maxVersion(PID, ID)).thenReturn(2);

        ResolvedVersion v = service().setLabels(PID, ID, 2, BY, Set.of("production"), LabelMode.ADD);

        verify(repo).upsertLabel(PID, ID, "production", "v2", BY, NOW);
        verify(repo, never()).deleteLabel(anyString(), anyString(), anyString());
        assertEquals(List.of("production", "staging", "latest"), v.labels());
    }

    @Test
    void setLabels_rejectsLatest() {
        assertError(
                PromptsError.LATEST_RESERVED,
                () -> service().setLabels(PID, ID, 1, BY, Set.of("latest"), LabelMode.ADD));
        verifyNoInteractions(repo);
    }

    /**
     * A name lookup runs before any name validation: the public POST, PATCH and DELETE and the fetch all read the
     * name first. The bug: a name with a NUL character reaches the SELECT, the driver refuses the parameter, and the
     * request is a 500. A NUL name can never be stored, so it names no prompt.
     */
    @Test
    void nulInALookedUpNameIsAbsentWithoutAQuery() {
        assertTrue(service().findByName(PID, "a\u0000b").isEmpty());
        assertError(PromptsError.NOT_FOUND, () -> service().resolve(PID, "a\u0000b", null, null));
        verifyNoInteractions(repo);
    }

    /**
     * Every label write locks the prompt row before a label row. The bug: removing a label deletes the label row first
     * and touches the prompt row after, so a concurrent label move on the same prompt deadlocks (40P01) and one of the
     * two requests is a 500. A label in {@code removeLabels} that sits on another version stays there.
     */
    @Test
    void setLabels_removeLabelsLocksThePromptBeforeTheLabelRow() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 2)).thenReturn(Optional.of(version(2)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("dev", 3), label("staging", 2)));
        when(repo.maxVersion(PID, ID)).thenReturn(3);

        ResolvedVersion v = service().setLabels(PID, ID, 2, BY, Set.of(), Set.of("staging", "dev"), LabelMode.ADD);

        InOrder order = inOrder(repo);
        order.verify(repo).lockPrompt(PID, ID);
        order.verify(repo).deleteLabel(PID, ID, "staging");
        order.verify(repo).touchPrompt(PID, ID, NOW);
        verify(repo, never()).deleteLabel(PID, ID, "dev");
        assertEquals(List.of(), v.labels());
    }

    /**
     * {@code removeLabels} runs after {@code newLabels}, so a label in both lists would depend on processing order;
     * {@code latest} is never stored. The bugs: either list is half applied before the refusal, or {@code latest}
     * reaches the label table.
     */
    @Test
    void setLabels_refusesALabelInBothListsAndLatestInRemove() {
        assertError(
                CommonError.INVALID_PARAMETER,
                () -> service().setLabels(PID, ID, 2, BY, Set.of("staging"), Set.of("staging"), LabelMode.ADD));
        assertError(
                PromptsError.LATEST_RESERVED,
                () -> service().setLabels(PID, ID, 2, BY, Set.of(), Set.of("latest"), LabelMode.ADD));
        verifyNoInteractions(repo);
    }

    static Stream<Arguments> configsWithNul() {
        return Stream.of(
                Arguments.of(Map.of("system", "secret\u0000")),
                Arguments.of(Map.of("k\u0000", 1)),
                Arguments.of(Map.of("stop", List.of("a", Map.of("x", "b\u0000")))));
    }

    /**
     * Jackson writes U+0000 as the escape Postgres refuses in jsonb (22P05). The bug: a config value, key or nested
     * string with a NUL character reaches insertVersion and the request is a 500 that logs the JSON.
     */
    @ParameterizedTest
    @MethodSource("configsWithNul")
    void createVersion_nulInConfigIsInvalidParameter(Map<String, Object> config) {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));

        CreateVersionCommand cmd = new CreateVersionCommand("x", config, List.of(), null, null);
        TessaryException e =
                assertThrows(TessaryException.class, () -> service().createVersion(PID, ID, BY, CreateVia.UI, cmd));
        assertEquals(CommonError.INVALID_PARAMETER, e.error());
        assertEquals("Invalid parameter: config holds a NUL character (U+0000)", e.getMessage());
        verify(repo, never()).insertVersion(any());
    }

    /**
     * The bug: a NUL check that scans the serialized JSON for the escape text also refuses a config whose string only
     * mentions that escape (Jackson writes its backslash doubled). Such a config holds no NUL and is stored.
     */
    @Test
    void createVersion_configMentioningTheEscapeIsStored() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.claimVersion(PID, ID)).thenReturn(2);

        CreateVersionCommand cmd =
                new CreateVersionCommand("x", Map.of("note", "\\u0000", "n", 5), List.of(), null, null);
        assertEquals(
                Map.of("note", "\\u0000", "n", 5),
                service().createVersion(PID, ID, BY, CreateVia.UI, cmd).config());
    }

    /** The commit message is a text column; Postgres refuses a NUL byte in text (22021). */
    @Test
    void nulInCommitMessageIsInvalidParameterBeforeAnyWrite() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));

        CreateVersionCommand version = new CreateVersionCommand("x", null, List.of(), "fix\u0000", null);
        assertError(CommonError.INVALID_PARAMETER, () -> service().createVersion(PID, ID, BY, CreateVia.UI, version));
        CreatePromptCommand prompt =
                new CreatePromptCommand("p", PromptType.TEXT, "hi", null, List.of(), List.of(), "fix\u0000");
        assertError(CommonError.INVALID_PARAMETER, () -> service().create(PID, BY, CreateVia.UI, prompt));
        verify(repo, never()).insertPrompt(any());
        verify(repo, never()).insertVersion(any());
    }

    /**
     * The second guard. The bug: a value the database refuses surfaces as a 500 whose logged message carries the
     * SQL error context, which quotes the stored JSON. Expected: a 400 with no cause attached.
     */
    @Test
    void writeVersion_integrityViolationIsA400WithoutTheSqlCause() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.claimVersion(PID, ID)).thenReturn(2);
        doThrow(new DataIntegrityViolationException("JSON data, line 1: \"secret system text"))
                .when(repo)
                .insertVersion(any());

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> service().createVersion(PID, ID, BY, CreateVia.UI, textVersion(List.of())));
        assertEquals(CommonError.INVALID_BODY, e.error());
        assertEquals(null, e.getCause());
        assertEquals("Malformed request body: the prompt version holds a value that cannot be stored", e.getMessage());
    }

    @Test
    void updateTags_integrityViolationIsA400WithoutTheSqlCause() {
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        doThrow(new DataIntegrityViolationException("tags rejected")).when(repo).updateTags(PID, ID, List.of("a"), NOW);

        TessaryException e =
                assertThrows(TessaryException.class, () -> service().updateTags(PID, ID, List.of("a")));
        assertEquals(CommonError.INVALID_BODY, e.error());
        assertEquals("Malformed request body: the tags hold a value that cannot be stored", e.getMessage());
        assertEquals(null, e.getCause());
    }

    /**
     * The offset is (page - 1) * limit. The bug: in int arithmetic a huge page overflows to a negative OFFSET, which
     * Postgres refuses with a 500 on every list route.
     */
    @Test
    void list_pageWhoseOffsetOverflowsIsInvalidParameter() {
        PromptListQuery q = new PromptListQuery(
                null, null, null, null, null, null, null, null, PromptListQuery.Order.NAME, Integer.MAX_VALUE, 200);

        TessaryException e =
                assertThrows(TessaryException.class, () -> service().list(PID, q));
        assertEquals(CommonError.INVALID_PARAMETER, e.error());
        assertEquals("Invalid parameter: page", e.getMessage());
        verifyNoInteractions(repo);
    }

    /** Bug: the driver refuses U+0000 in a parameter, so a NUL in a label or a filter reached it and answered 500. */
    @Test
    void resolve_aLabelHoldingNulNamesNoLabel_withoutAQuery() {
        TessaryException e =
                assertThrows(TessaryException.class, () -> service().resolve(PID, "p", null, "prod\u0000"));

        assertEquals(PromptsError.LABEL_NOT_FOUND, e.error());
        verifyNoInteractions(repo);
    }

    static Stream<PromptListQuery> filtersHoldingNul() {
        PromptListQuery.Order o = PromptListQuery.Order.NAME;
        return Stream.of(
                new PromptListQuery("a\u0000", null, null, null, null, null, null, null, o, 1, 10),
                new PromptListQuery(null, "f\u0000", null, null, null, null, null, null, o, 1, 10),
                new PromptListQuery(null, null, "q\u0000", null, null, null, null, null, o, 1, 10),
                new PromptListQuery(null, null, null, "l\u0000", null, null, null, null, o, 1, 10),
                new PromptListQuery(null, null, null, null, "\u0000", null, null, null, o, 1, 10));
    }

    @ParameterizedTest
    @MethodSource("filtersHoldingNul")
    void list_andFolders_aFilterHoldingNulMatchesNothing_withoutAQuery(PromptListQuery q) {
        PromptService.PromptPage page = service().list(PID, q);

        assertEquals(List.of(), page.items());
        assertEquals(0, page.total());
        assertEquals(List.of(), service().folders(PID, q));
        verifyNoInteractions(repo);
    }

    /** Bug: {@code ?label=} named the label "" (LABEL_NOT_FOUND), and beside a version was BOTH_VERSION_AND_LABEL. */
    @Test
    void resolve_aBlankLabelIsNoLabel() {
        when(repo.resolve(PID, "p", new PromptSelector.Label("production")))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, version(2), List.of("production"), 3)));
        when(repo.resolve(PID, "p", new PromptSelector.Version(3)))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, version(3), List.of(), 3)));

        assertEquals(2, service().resolve(PID, "p", null, " ").version().version());
        assertEquals(3, service().resolve(PID, "p", 3, "").version().version());
    }

    @Test
    void resolve_defaultsToProductionAndLatestIsDerived() {
        when(repo.resolve(PID, "p", new PromptSelector.Label("production")))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, version(2), List.of("production"), 3)));
        when(repo.resolve(PID, "p", new PromptSelector.Latest()))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, version(3), List.of(), 3)));

        ResolvedVersion production = service().resolve(PID, "p", null, null);
        assertEquals(2, production.version().version());
        assertEquals(List.of("production"), production.labels());
        assertEquals(new PromptBody.Text("body 2"), production.body());

        ResolvedVersion latest = service().resolve(PID, "p", null, "latest");
        assertEquals(3, latest.version().version());
        assertEquals(List.of("latest"), latest.labels());

        assertError(PromptsError.BOTH_VERSION_AND_LABEL, () -> service().resolve(PID, "p", 2, "x"));
    }

    @Test
    void resolve_labelAndItsVersionAreOneRead() {
        // Bug: resolve read the label, then re-read its version by number. A promote and a delete of the old version
        // between the two reads turned a live production label into VERSION_NOT_FOUND.
        when(repo.resolve(PID, "p", new PromptSelector.Label("production")))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, version(3), List.of("production"), 3)));

        ResolvedVersion production = service().resolve(PID, "p", null, null);

        assertEquals(3, production.version().version());
        assertEquals(List.of("production", "latest"), production.labels());
    }

    @Test
    void resolve_latestIsOneRead() {
        // Bug: resolve read the highest number, then re-read that version by number. Deleting the top version between
        // the two reads turned latest, which always exists, into VERSION_NOT_FOUND.
        when(repo.resolve(PID, "p", new PromptSelector.Latest()))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, version(2), List.of(), 2)));

        ResolvedVersion latest = service().resolve(PID, "p", null, "latest");

        assertEquals(2, latest.version().version());
        assertEquals(List.of("latest"), latest.labels());
    }

    @Test
    void resolve_noProductionThrowsLabelNotFound() {
        when(repo.resolve(PID, "p", new PromptSelector.Label("production")))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, null, List.of(), 1)));

        TessaryException e =
                assertThrows(TessaryException.class, () -> service().resolve(PID, "p", null, null));
        assertEquals(PromptsError.LABEL_NOT_FOUND, e.error());
        assertEquals("Prompt p has no label production", e.getMessage());
    }

    @Test
    void resolve_missingNameAndVersionAre404() {
        when(repo.resolve(PID, "nope", new PromptSelector.Label("production"))).thenReturn(Optional.empty());
        assertError(PromptsError.NOT_FOUND, () -> service().resolve(PID, "nope", null, null));

        when(repo.resolve(PID, "p", new PromptSelector.Version(9)))
                .thenReturn(Optional.of(new PromptResolveRow(TEXT_PROMPT, null, List.of(), 1)));
        TessaryException e =
                assertThrows(TessaryException.class, () -> service().resolve(PID, "p", 9, null));
        assertEquals(PromptsError.VERSION_NOT_FOUND, e.error());
        assertEquals("Prompt p has no version 9", e.getMessage());
    }

    /**
     * A version that carries labels can be deleted; its labels go with it by the FK cascade. The bug: a
     * delete that tool allows answers 409 here.
     */
    @Test
    void deleteVersion_removesALabeledVersionAndItsLabels() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 2)).thenReturn(Optional.of(version(2)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("production", 2)));
        when(repo.deleteVersion(PID, ID, 2)).thenReturn(true);
        when(repo.maxVersion(PID, ID)).thenReturn(1);

        assertTrue(service().deleteVersion(PID, ID, 2));

        verify(repo).touchPrompt(PID, ID, NOW);
        verify(repo, never()).deletePrompt(any(), any());
    }

    /** The bug: deleting the only version leaves an empty prompt row that no fetch can read. */
    @Test
    void deleteVersion_lastVersionRemovesThePrompt() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 1)).thenReturn(Optional.of(version(1)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of());
        when(repo.deleteVersion(PID, ID, 1)).thenReturn(true);
        when(repo.maxVersion(PID, ID)).thenReturn(0);

        assertTrue(service().deleteVersion(PID, ID, 1));

        verify(repo).deletePrompt(PID, ID);
    }

    /**
     * Delete by label deletes the version the label points at, read under the prompt lock so a concurrent move cannot
     * send the delete to the wrong version. The bugs: the label alone is removed and the version stays, and an unknown
     * label answers 200.
     */
    @Test
    void deleteVersionByLabel_deletesTheVersionItPointsAt_unknownIsLabelNotFound() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.labelVersion(PID, ID, "staging")).thenReturn(Optional.of(2));
        when(repo.findVersion(PID, ID, 2)).thenReturn(Optional.of(version(2)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("staging", 2)));
        when(repo.deleteVersion(PID, ID, 2)).thenReturn(true);
        when(repo.maxVersion(PID, ID)).thenReturn(3);

        assertTrue(service().deleteVersionByLabel(PID, ID, "staging"));
        InOrder order = inOrder(repo);
        order.verify(repo).lockPrompt(PID, ID);
        order.verify(repo).labelVersion(PID, ID, "staging");
        order.verify(repo).deleteVersion(PID, ID, 2);

        TessaryException e =
                assertThrows(TessaryException.class, () -> service().deleteVersionByLabel(PID, ID, "canary"));
        assertEquals(PromptsError.LABEL_NOT_FOUND, e.error());
        assertEquals("Prompt p has no label canary", e.getMessage());
    }

    private static PromptVersionSummaryRow summary(int n) {
        return new PromptVersionSummaryRow("v" + n, n, null, BY, CreateVia.UI, NOW);
    }

    /**
     * The detail page reads versions a page at a time. The bugs: {@code latest_version} taken from the page's first
     * row reads 3 on the page of older versions when the prompt's highest is 5, and the page cannot tell that a page of
     * still older versions exists.
     */
    @Test
    void get_pageOfOlderVersionsKnowsTheLatestAndWhetherMoreExist() {
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.versionsOf(PID, ID, 3, 4)).thenReturn(List.of(summary(3), summary(2), summary(1)));
        when(repo.versionsOf(PID, ID, 3, 2)).thenReturn(List.of(summary(1)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of());
        when(repo.maxVersion(PID, ID)).thenReturn(5);

        PromptService.PromptDetail older = service().get(PID, ID, 2, 4);
        assertEquals(List.of(summary(3), summary(2)), older.versions());
        assertEquals(5, older.latestVersion());
        assertTrue(older.hasOlderVersions());

        PromptService.PromptDetail oldest = service().get(PID, ID, 2, 2);
        assertEquals(List.of(summary(1)), oldest.versions());
        assertFalse(oldest.hasOlderVersions());
    }

    /** The production fetch of {@code p}, answering {@code first} and then {@code then} on the next read. */
    private void stubProductionReads(PromptRow first, PromptRow then) {
        when(repo.resolve(PID, "p", new PromptSelector.Label("production")))
                .thenReturn(
                        Optional.of(new PromptResolveRow(first, version(1), List.of("production"), 1)),
                        Optional.of(new PromptResolveRow(then, version(1), List.of("production"), 1)));
    }

    @Test
    void resolve_aSecondFetchIsServedFromTheCache() {
        // Bug: the fetch path skips the cache, so every SDK fetch reads the database.
        PromptRow tagged = new PromptRow(ID, PID, "p", PromptType.TEXT, List.of("new"), 1, BY, NOW, NOW);
        stubProductionReads(TEXT_PROMPT, tagged);
        PromptService service = service();
        service.resolve(PID, "p", null, null);

        assertEquals(List.of(), service.resolve(PID, "p", null, null).prompt().tags());
    }

    @Test
    void updateTags_invalidatesTheFetchCache() {
        // Bug: the tag write runs outside a transaction, so an after-commit hook alone never fires and a fetch keeps
        // the old tags for the full TTL.
        PromptRow tagged = new PromptRow(ID, PID, "p", PromptType.TEXT, List.of("new"), 1, BY, NOW, NOW);
        stubProductionReads(TEXT_PROMPT, tagged);
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        PromptService service = service();
        service.resolve(PID, "p", null, null);

        service.updateTags(PID, ID, List.of("new"));

        assertEquals(
                List.of("new"), service.resolve(PID, "p", null, null).prompt().tags());
    }

    @Test
    void deleteFolder_invalidatesTheFetchCache() {
        // Bug: the folder delete runs outside a transaction, so a fetch keeps serving a deleted prompt for the TTL.
        PromptRow moved = new PromptRow(ID, PID, "p", PromptType.TEXT, List.of("after"), 1, BY, NOW, NOW);
        stubProductionReads(TEXT_PROMPT, moved);
        when(repo.deleteFolder(PID, "team/")).thenReturn(1);
        PromptService service = service();
        service.resolve(PID, "p", null, null);

        service.deleteFolder(PID, "team");

        assertEquals(
                List.of("after"), service.resolve(PID, "p", null, null).prompt().tags());
    }

    @Test
    void deleteFolder_validatesPath() {
        assertError(PromptsError.INVALID_NAME, () -> service().deleteFolder(PID, "team/"));
        assertError(PromptsError.INVALID_NAME, () -> service().deleteFolder(PID, "a|b"));
        verifyNoInteractions(repo);

        when(repo.deleteFolder(PID, "team/x/")).thenReturn(2);
        assertEquals(2, service().deleteFolder(PID, "team/x"));
    }

    /**
     * A name may hold a space before a slash, so {@code My Team /a} sits in the folder {@code "My Team "}. The bug: the
     * folder path is trimmed like a name, and deleting {@code "My Team "} deletes the prompts of {@code My Team}.
     */
    @Test
    void deleteFolder_keepsTheFolderPathAsGiven() {
        when(repo.deleteFolder(PID, "My Team /")).thenReturn(1);

        assertEquals(1, service().deleteFolder(PID, "My Team "));
        assertError(PromptsError.INVALID_NAME, () -> service().deleteFolder(PID, "  "));
    }

    static Stream<Arguments> writesAndReadsById() {
        return Stream.<Consumer<PromptService>>of(
                        s -> s.get(PID, ID),
                        s -> s.getVersion(PID, ID, 1),
                        s -> s.setLabels(PID, ID, 1, BY, Set.of("production"), LabelMode.REPLACE),
                        s -> s.deleteVersion(PID, ID, 1),
                        s -> s.createVersion(PID, ID, BY, CreateVia.UI, textVersion(List.of())),
                        s -> s.updateTags(PID, ID, List.of("a")),
                        s -> s.deleteVersionByLabel(PID, ID, "staging"))
                .map(Arguments::of);
    }

    /** Every read is keyed on project_id, so another project's id reads as absent here. */
    @ParameterizedTest
    @MethodSource("writesAndReadsById")
    void otherProjectsPromptIsNotFound(Consumer<PromptService> call) {
        assertError(PromptsError.NOT_FOUND, () -> call.accept(service()));
        verify(repo, never()).insertVersion(any());
        verify(repo, never()).upsertLabel(any(), any(), any(), any(), any(), any());
        verify(repo, never()).updateTags(any(), any(), any(), any());
        verify(repo, never()).deleteVersion(any(), any(), eq(1));
        verify(repo, never()).deleteLabel(any(), any(), any());
    }

    @Test
    void otherProjectsPromptDeleteIsFalse() {
        assertFalse(service().delete(PID, ID));
        verify(repo, never()).deletePrompt(any(), any());
    }

    @Test
    void forMcp_isPageOneLimit100Updated() {
        assertEquals(
                new PromptListQuery(
                        null, "t", null, "l", null, null, null, null, PromptListQuery.Order.UPDATED, 1, 100),
                PromptListQuery.forMcp("t", "l"));
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(TessaryException.class, call).error());
    }
}
