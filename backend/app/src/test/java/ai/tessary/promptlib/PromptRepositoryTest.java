// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.PromptListQuery;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Prompt storage against real Postgres. The bugs: a column dropped or mistyped on write, a folder or search filter
 * that treats {@code _} or {@code %} as a wildcard, a facet that ignores its argument, a read that crosses projects,
 * a list page whose versions or last config come from the wrong row, and a version claim that does not raise
 * {@code last_version}.
 */
@SpringBootTest
record PromptRepositoryTest(PromptRepository repo, TenantService tenants) {

    private static final Instant T1 = Instant.parse("2026-10-18T09:12:44.118Z");
    private static final Instant T2 = Instant.parse("2026-10-18T10:00:00.250Z");
    private static final Instant T3 = Instant.parse("2026-10-18T11:30:00.001Z");

    private String project(String name) {
        return TenantFixture.bootstrap(tenants, name).project().id();
    }

    private PromptRow prompt(String pid, String name, List<String> tags) {
        PromptRow row = new PromptRow(Ids.ulid(), pid, name, PromptType.TEXT, tags, 0, "user-1", T1, T1);
        repo.insertPrompt(row);
        return row;
    }

    private PromptVersionRow version(PromptRow p, int n, String configJson) {
        PromptVersionRow row = new PromptVersionRow(
                Ids.ulid(), p.projectId(), p.id(), n, "\"v" + n + "\"", configJson, null, "user-1", CreateVia.UI, T1);
        repo.insertVersion(row);
        return row;
    }

    private static PromptListQuery query(
            @Nullable String folder, @Nullable String q, @Nullable String label, @Nullable String tag) {
        return new PromptListQuery(null, folder, q, label, tag, null, null, null, PromptListQuery.Order.NAME, 1, 50);
    }

    private List<String> names(String pid, PromptListQuery q) {
        return repo.list(pid, q, 0, 50).stream().map(r -> r.prompt().name()).toList();
    }

    @Test
    void insertVersion_roundTripsEveryColumn() {
        String pid = project("prompt-repo-roundtrip");
        PromptRow prompt =
                new PromptRow(Ids.ulid(), pid, "team/a", PromptType.CHAT, List.of("x", "y"), 1, "user-1", T1, T2);
        repo.insertPrompt(prompt);
        // jsonb hands text back in its own canonical spacing and key order, so the row is written in that form.
        PromptVersionRow version = new PromptVersionRow(
                Ids.ulid(),
                pid,
                prompt.id(),
                1,
                "[{\"role\": \"user\", \"content\": \"hi {{name}}\"}, {\"name\": \"history\", \"type\": \"placeholder\"}]",
                "{\"temperature\": 0.2}",
                "first",
                "user-2",
                CreateVia.API,
                T2);
        repo.insertVersion(version);
        repo.upsertLabel(pid, prompt.id(), "production", version.id(), "user-3", T3);

        assertEquals(Optional.of(prompt), repo.findById(pid, prompt.id()));
        assertEquals(Optional.of(prompt), repo.findByName(pid, "team/a"));
        assertEquals(Optional.of(version), repo.findVersion(pid, prompt.id(), 1));
        assertEquals(List.of(version), repo.versionsOf(pid, prompt.id()));
        assertEquals(
                List.of(new PromptLabelRow(pid, prompt.id(), "production", version.id(), 1, "user-3", T3)),
                repo.labelsOf(pid, List.of(prompt.id())));
        assertEquals(Optional.of(1), repo.labelVersion(pid, prompt.id(), "production"));
        assertEquals(1, repo.maxVersion(pid, prompt.id()));
        assertEquals(2, repo.claimVersion(pid, prompt.id()));
        // A claim raises last_version only; latest still reads the versions that exist.
        assertEquals(1, repo.maxVersion(pid, prompt.id()));

        repo.updateTags(pid, prompt.id(), List.of("z"), T3);
        assertEquals(
                Optional.of(
                        new PromptRow(prompt.id(), pid, "team/a", PromptType.CHAT, List.of("z"), 2, "user-1", T1, T3)),
                repo.findById(pid, prompt.id()));
    }

    /** A second upsert of the same label moves it, so one label points at one version. */
    @Test
    void upsertLabel_movesTheLabelAndDeleteRemovesIt() {
        String pid = project("prompt-repo-move");
        PromptRow p = prompt(pid, "p", List.of());
        PromptVersionRow v1 = version(p, 1, "{}");
        PromptVersionRow v2 = version(p, 2, "{}");
        repo.upsertLabel(pid, p.id(), "production", v1.id(), "user-1", T1);
        repo.upsertLabel(pid, p.id(), "production", v2.id(), "user-2", T2);
        repo.touchPrompt(pid, p.id(), T3);

        assertEquals(
                List.of(new PromptLabelRow(pid, p.id(), "production", v2.id(), 2, "user-2", T2)),
                repo.labelsOf(pid, List.of(p.id())));
        assertEquals(T3, repo.findById(pid, p.id()).orElseThrow().updatedAt());
        assertTrue(repo.deleteLabel(pid, p.id(), "production"));
        assertFalse(repo.deleteLabel(pid, p.id(), "production"));
        assertEquals(List.of(), repo.labelsOf(pid, List.of()));
        assertTrue(repo.deleteVersion(pid, p.id(), 1));
        assertEquals(1, repo.countVersions(pid, p.id()));
        assertTrue(repo.deletePrompt(pid, p.id()));
        assertEquals(Optional.empty(), repo.findById(pid, p.id()));
    }

    /** A label or {@code latest} reads its version row in one statement, scoped to the project. */
    @Test
    void findVersionByLabel_andFindLatestVersion() {
        String pid = project("prompt-repo-by-label");
        String other = project("prompt-repo-by-label-other");
        PromptRow p = prompt(pid, "p", List.of());
        PromptVersionRow v1 = version(p, 1, "{}");
        PromptVersionRow v2 = version(p, 2, "{}");
        repo.upsertLabel(pid, p.id(), "production", v1.id(), "user-1", T1);
        repo.upsertLabel(pid, p.id(), "staging", v2.id(), "user-1", T1);

        assertEquals(Optional.of(v1), repo.findVersionByLabel(pid, p.id(), "production"));
        assertEquals(Optional.of(v2), repo.findVersionByLabel(pid, p.id(), "staging"));
        assertEquals(Optional.empty(), repo.findVersionByLabel(pid, p.id(), "canary"));
        assertEquals(Optional.empty(), repo.findVersionByLabel(other, p.id(), "production"));
        assertEquals(Optional.of(v2), repo.findLatestVersion(pid, p.id()));
        assertEquals(Optional.empty(), repo.findLatestVersion(other, p.id()));
    }

    @Test
    void list_folderUsesPrefixNotLike() {
        String pid = project("prompt-repo-folder");
        for (String name : List.of("team/a", "team_x/b", "teamax/c", "teammate/d")) {
            version(prompt(pid, name, List.of()), 1, "{}");
        }

        assertEquals(List.of("team/a"), names(pid, query("team", null, null, null)));
        assertEquals(1, repo.count(pid, query("team", null, null, null)));
        // As a LIKE pattern, team_x/ would also match teamax/.
        assertEquals(List.of("team_x/b"), names(pid, query("team_x", null, null, null)));
        assertEquals(1, repo.deleteFolder(pid, "team_x/"));
        assertEquals(1, repo.deleteFolder(pid, "team/"));
        assertEquals(Set.of("teamax/c", "teammate/d"), Set.copyOf(names(pid, query(null, null, null, null))));
    }

    @Test
    void list_qEscapesWildcards() {
        String pid = project("prompt-repo-q");
        version(prompt(pid, "100%", List.of()), 1, "{}");
        version(prompt(pid, "1000", List.of()), 1, "{}");
        version(prompt(pid, "a_b", List.of()), 1, "{}");
        version(prompt(pid, "axb", List.of()), 1, "{}");

        assertEquals(List.of("100%"), names(pid, query(null, "100%", null, null)));
        assertEquals(List.of("a_b"), names(pid, query(null, "a_", null, null)));
        assertEquals(Set.of("100%", "1000"), Set.copyOf(names(pid, query(null, "10", null, null))));
    }

    @Test
    void list_tagAndLabelFacets() {
        String pid = project("prompt-repo-facets");
        PromptRow tagged = prompt(pid, "tagged", List.of("a"));
        PromptVersionRow v = version(tagged, 1, "{}");
        repo.upsertLabel(pid, tagged.id(), "staging", v.id(), "user-1", T1);
        version(prompt(pid, "plain", List.of("b")), 1, "{}");

        assertEquals(List.of("tagged"), names(pid, query(null, null, null, "a")));
        assertEquals(List.of("tagged"), names(pid, query(null, null, "staging", null)));
        assertEquals(List.of("plain", "tagged"), names(pid, query(null, null, "latest", null)));
        assertEquals(1, repo.count(pid, query(null, null, "staging", "a")));
    }

    /** Exact name, type and the updated window each narrow the list; UPDATED orders newest first. */
    @Test
    void list_nameTypeWindowAndOrder() {
        String pid = project("prompt-repo-window");
        PromptRow old = prompt(pid, "old", List.of());
        version(old, 1, "{}");
        PromptRow fresh = prompt(pid, "fresh", List.of());
        version(fresh, 1, "{}");
        repo.touchPrompt(pid, fresh.id(), T3);

        assertEquals(
                List.of("fresh"),
                names(
                        pid,
                        new PromptListQuery(
                                "fresh",
                                null,
                                null,
                                null,
                                null,
                                PromptType.TEXT,
                                null,
                                null,
                                PromptListQuery.Order.NAME,
                                1,
                                50)));
        assertEquals(
                List.of(),
                names(
                        pid,
                        new PromptListQuery(
                                null,
                                null,
                                null,
                                null,
                                null,
                                PromptType.CHAT,
                                null,
                                null,
                                PromptListQuery.Order.NAME,
                                1,
                                50)));
        assertEquals(
                List.of("fresh"),
                names(
                        pid,
                        new PromptListQuery(
                                null, null, null, null, null, null, T2, T3, PromptListQuery.Order.NAME, 1, 50)));
        assertEquals(
                List.of("fresh", "old"),
                names(
                        pid,
                        new PromptListQuery(
                                null, null, null, null, null, null, null, null, PromptListQuery.Order.UPDATED, 1, 50)));
        assertEquals(
                List.of("old"),
                repo.list(pid, query(null, null, null, null), 1, 1).stream()
                        .map(r -> r.prompt().name())
                        .toList());
    }

    @Test
    void list_isProjectScoped() {
        String pidA = project("prompt-repo-scope-a");
        String pidB = project("prompt-repo-scope-b");
        version(prompt(pidA, "same", List.of()), 1, "{}");
        PromptRow b = prompt(pidB, "same", List.of());
        version(b, 1, "{}");

        assertEquals(List.of("same"), names(pidA, query(null, null, null, null)));
        assertEquals(List.of("same"), names(pidB, query(null, null, null, null)));
        assertEquals(Optional.empty(), repo.findById(pidA, b.id()));
        assertFalse(repo.deleteVersion(pidA, b.id(), 1));
        assertEquals(Optional.empty(), repo.lockPrompt(pidA, b.id()));
        assertFalse(repo.deletePrompt(pidA, b.id()));
        assertEquals(Optional.of(b.id()), repo.lockPrompt(pidB, b.id()));
    }

    @Test
    void list_carriesVersionsAndLastConfig() {
        String pid = project("prompt-repo-last");
        PromptRow p = prompt(pid, "p", List.of());
        version(p, 1, "{\"a\": 1}");
        version(p, 3, "{\"a\": 3}");
        version(p, 2, "{\"a\": 2}");

        PromptListRow row = repo.list(pid, query(null, null, null, null), 0, 50).getFirst();
        assertEquals(List.of(1, 2, 3), row.versions());
        assertEquals("{\"a\": 3}", row.lastConfigJson());
        assertEquals(3, row.latestVersion());
        assertEquals(3, row.versionCount());
    }

    /** The CHECK is the backstop under the Java name rule, which must reject this name before it reaches SQL. */
    @Test
    void unicodeSpaceNameFailsTheCheck() {
        String pid = project("prompt-repo-unicode");
        assertThrows(DataIntegrityViolationException.class, () -> prompt(pid, "a　b", List.of()));
    }
}
