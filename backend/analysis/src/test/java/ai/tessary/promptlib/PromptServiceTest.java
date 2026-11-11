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
        return new PromptService(repo, principals, Clock.fixed(NOW, ZoneOffset.UTC));
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
        return new CreateVersionCommand("hello {{name}}", null, labels, null);
    }

    @Test
    void createVersion_numbersFromTheClaimedVersion() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.countVersions(PID, ID)).thenReturn(3);
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
        when(repo.countVersions(PID, ID)).thenReturn(1);
        when(repo.claimVersion(PID, ID)).thenReturn(2);
        doThrow(new DuplicateKeyException("ux_prompt_version")).when(repo).insertVersion(any());

        assertError(
                PromptsError.CONCURRENT_WRITE,
                () -> service().createVersion(PID, ID, BY, CreateVia.UI, textVersion(List.of())));
    }

    @Test
    void createVersion_thousandthVersionIsTheLimit() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.countVersions(PID, ID)).thenReturn(1000);

        assertError(
                PromptsError.VERSION_LIMIT,
                () -> service().createVersion(PID, ID, BY, CreateVia.UI, textVersion(List.of())));
        verify(repo, never()).insertVersion(any());
    }

    @Test
    void createVersion_typeComesFromThePrompt() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));

        CreateVersionCommand chatBody =
                new CreateVersionCommand(List.of(Map.of("role", "user", "content", "x")), null, List.of(), null);
        assertError(PromptsError.INVALID_BODY, () -> service().createVersion(PID, ID, BY, CreateVia.UI, chatBody));
        verify(repo, never()).insertVersion(any());
    }

    /** A config is a JSON object of bounded size; a 70000-character value is refused before any write. */
    @Test
    void createVersion_oversizeConfigIsInvalidConfig() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));

        CreateVersionCommand big = new CreateVersionCommand("x", Map.of("k", "v".repeat(70_000)), List.of(), null);
        assertError(PromptsError.INVALID_CONFIG, () -> service().createVersion(PID, ID, BY, CreateVia.UI, big));
        verify(repo, never()).insertVersion(any());
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
        when(repo.labelVersion(PID, ID, "production")).thenReturn(Optional.of(2));
        when(repo.maxVersion(PID, ID)).thenReturn(3);

        ResolvedVersion v = service().setLabels(PID, ID, 2, BY, Set.of("production"), LabelMode.REPLACE);

        verify(repo).deleteLabel(PID, ID, "staging");
        verify(repo, never()).deleteLabel(PID, ID, "dev");
        verify(repo).touchPrompt(PID, ID, NOW);
        assertEquals(List.of("production"), v.labels());
        assertFalse(v.isLatest());
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
     * Every other label write locks the prompt row before a label row. The bug: removeLabel deletes the label row first
     * and touches the prompt row after, so a concurrent label move on the same prompt deadlocks (40P01) and one of the
     * two requests is a 500.
     */
    @Test
    void removeLabel_locksThePromptBeforeTheLabelRow() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.deleteLabel(PID, ID, "staging")).thenReturn(true);

        assertTrue(service().removeLabel(PID, ID, "staging"));

        InOrder order = inOrder(repo);
        order.verify(repo).lockPrompt(PID, ID);
        order.verify(repo).deleteLabel(PID, ID, "staging");
        order.verify(repo).touchPrompt(PID, ID, NOW);
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

        CreateVersionCommand cmd = new CreateVersionCommand("x", config, List.of(), null);
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
        when(repo.countVersions(PID, ID)).thenReturn(1);
        when(repo.claimVersion(PID, ID)).thenReturn(2);

        CreateVersionCommand cmd = new CreateVersionCommand("x", Map.of("note", "\\u0000", "n", 5), List.of(), null);
        assertEquals(
                Map.of("note", "\\u0000", "n", 5),
                service().createVersion(PID, ID, BY, CreateVia.UI, cmd).config());
    }

    /** The commit message is a text column; Postgres refuses a NUL byte in text (22021). */
    @Test
    void nulInCommitMessageIsInvalidParameterBeforeAnyWrite() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));

        CreateVersionCommand version = new CreateVersionCommand("x", null, List.of(), "fix\u0000");
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
        when(repo.countVersions(PID, ID)).thenReturn(1);
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

    @Test
    void resolve_defaultsToProductionAndLatestIsDerived() {
        when(repo.findByName(PID, "p")).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersionByLabel(PID, ID, "production")).thenReturn(Optional.of(version(2)));
        when(repo.findLatestVersion(PID, ID)).thenReturn(Optional.of(version(3)));
        // dev sorts first, so a label list that took the first label instead of the version's own reads dev.
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("dev", 1), label("production", 2)));
        when(repo.maxVersion(PID, ID)).thenReturn(3);

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
        when(repo.findByName(PID, "p")).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersionByLabel(PID, ID, "production")).thenReturn(Optional.of(version(3)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("production", 3)));
        when(repo.maxVersion(PID, ID)).thenReturn(3);

        ResolvedVersion production = service().resolve(PID, "p", null, null);

        assertEquals(3, production.version().version());
        assertEquals(List.of("production", "latest"), production.labels());
    }

    @Test
    void resolve_latestIsOneRead() {
        // Bug: resolve read the highest number, then re-read that version by number. Deleting the top version between
        // the two reads turned latest, which always exists, into VERSION_NOT_FOUND.
        when(repo.findByName(PID, "p")).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findLatestVersion(PID, ID)).thenReturn(Optional.of(version(2)));
        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of());
        when(repo.maxVersion(PID, ID)).thenReturn(2);

        ResolvedVersion latest = service().resolve(PID, "p", null, "latest");

        assertEquals(2, latest.version().version());
        assertEquals(List.of("latest"), latest.labels());
    }

    @Test
    void resolve_noProductionThrowsLabelNotFound() {
        when(repo.findByName(PID, "p")).thenReturn(Optional.of(TEXT_PROMPT));

        TessaryException e =
                assertThrows(TessaryException.class, () -> service().resolve(PID, "p", null, null));
        assertEquals(PromptsError.LABEL_NOT_FOUND, e.error());
        assertEquals("Prompt p has no label production", e.getMessage());
    }

    @Test
    void resolve_missingNameAndVersionAre404() {
        when(repo.findByName(PID, "nope")).thenReturn(Optional.empty());
        assertError(PromptsError.NOT_FOUND, () -> service().resolve(PID, "nope", null, null));

        when(repo.findByName(PID, "p")).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 9)).thenReturn(Optional.empty());
        TessaryException e =
                assertThrows(TessaryException.class, () -> service().resolve(PID, "p", 9, null));
        assertEquals(PromptsError.VERSION_NOT_FOUND, e.error());
        assertEquals("Prompt p has no version 9", e.getMessage());
    }

    @Test
    void deleteVersion_rules() {
        when(repo.lockPrompt(PID, ID)).thenReturn(Optional.of(ID));
        when(repo.findById(PID, ID)).thenReturn(Optional.of(TEXT_PROMPT));
        when(repo.findVersion(PID, ID, 2)).thenReturn(Optional.of(version(2)));

        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("production", 2)));
        assertError(PromptsError.VERSION_LABELED, () -> service().deleteVersion(PID, ID, 2));

        when(repo.labelsOf(PID, List.of(ID))).thenReturn(List.of(label("production", 1)));
        when(repo.countVersions(PID, ID)).thenReturn(1);
        assertError(PromptsError.LAST_VERSION, () -> service().deleteVersion(PID, ID, 2));
        verify(repo, never()).deleteVersion(anyString(), anyString(), eq(2));

        when(repo.countVersions(PID, ID)).thenReturn(2);
        when(repo.deleteVersion(PID, ID, 2)).thenReturn(true);
        assertTrue(service().deleteVersion(PID, ID, 2));
        verify(repo).touchPrompt(PID, ID, NOW);
    }

    @Test
    void deleteFolder_validatesPath() {
        assertError(PromptsError.INVALID_NAME, () -> service().deleteFolder(PID, "team/"));
        assertError(PromptsError.INVALID_NAME, () -> service().deleteFolder(PID, "a b"));
        verifyNoInteractions(repo);

        when(repo.deleteFolder(PID, "team/x/")).thenReturn(2);
        assertEquals(2, service().deleteFolder(PID, "team/x"));
    }

    static Stream<Arguments> writesAndReadsById() {
        return Stream.<Consumer<PromptService>>of(
                        s -> s.get(PID, ID),
                        s -> s.getVersion(PID, ID, 1),
                        s -> s.setLabels(PID, ID, 1, BY, Set.of("production"), LabelMode.REPLACE),
                        s -> s.deleteVersion(PID, ID, 1),
                        s -> s.createVersion(PID, ID, BY, CreateVia.UI, textVersion(List.of())),
                        s -> s.updateTags(PID, ID, List.of("a")),
                        s -> s.removeLabel(PID, ID, "staging"))
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
