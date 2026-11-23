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
        assertEquals(
                List.of(new PromptVersionSummaryRow(version.id(), 1, "first", "user-2", CreateVia.API, T2)),
                repo.versionsOf(pid, prompt.id(), 10, null));
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

    /**
     * The fetch reads the prompt, the wanted version, that version's labels and the highest version number in one
     * statement. The bug: four reads with no shared snapshot, so a label move between them reports labels that belong
     * to another version, or {@code latest} on a version that is no longer the highest.
     */
    @Test
    void resolve_readsVersionLabelsAndLatestInOneStatement() {
        String pid = project("prompt-repo-resolve");
        String other = project("prompt-repo-resolve-other");
        PromptRow p = new PromptRow(Ids.ulid(), pid, "team/a", PromptType.CHAT, List.of("x"), 3, "user-1", T1, T2);
        repo.insertPrompt(p);
        PromptVersionRow v1 = version(p, 1, "{}");
        PromptVersionRow v2 = new PromptVersionRow(
                Ids.ulid(),
                pid,
                p.id(),
                2,
                "[{\"role\": \"user\", \"content\": \"hi\"}]",
                "{\"temperature\": 0.2}",
                "second",
                "user-2",
                CreateVia.API,
                T2);
        repo.insertVersion(v2);
        PromptVersionRow v3 = version(p, 3, "{}");
        repo.upsertLabel(pid, p.id(), "staging", v2.id(), "user-1", T1);
        repo.upsertLabel(pid, p.id(), "production", v2.id(), "user-1", T1);
        repo.upsertLabel(pid, p.id(), "dev", v1.id(), "user-1", T1);

        assertEquals(
                Optional.of(new PromptResolveRow(p, v2, List.of("production", "staging"), 3)),
                repo.resolve(pid, "team/a", new PromptSelector.Label("production")));
        assertEquals(
                Optional.of(new PromptResolveRow(p, v2, List.of("production", "staging"), 3)),
                repo.resolve(pid, "team/a", new PromptSelector.Version(2)));
        assertEquals(
                Optional.of(new PromptResolveRow(p, v3, List.of(), 3)),
                repo.resolve(pid, "team/a", new PromptSelector.Latest()));
        assertEquals(
                Optional.of(new PromptResolveRow(p, v1, List.of("dev"), 3)),
                repo.resolve(pid, "team/a", new PromptSelector.Label("dev")));
        assertEquals(Optional.empty(), repo.resolve(other, "team/a", new PromptSelector.Latest()));
        assertEquals(Optional.empty(), repo.resolve(other, "team/a", new PromptSelector.Label("production")));
    }

    /**
     * The bug: a missing label or version on a prompt that exists answers NOT_FOUND for the whole prompt, and the SDK
     * evicts the wrong thing. A prompt row with no matching version row tells the three apart.
     */
    @Test
    void resolve_tellsMissingPromptFromMissingVersionAndMissingLabel() {
        String pid = project("prompt-repo-resolve-missing");
        PromptRow p = prompt(pid, "p", List.of());
        version(p, 1, "{}");

        assertEquals(Optional.empty(), repo.resolve(pid, "nope", new PromptSelector.Latest()));
        assertEquals(
                Optional.of(new PromptResolveRow(p, null, List.of(), 1)),
                repo.resolve(pid, "p", new PromptSelector.Version(9)));
        assertEquals(
                Optional.of(new PromptResolveRow(p, null, List.of(), 1)),
                repo.resolve(pid, "p", new PromptSelector.Label("production")));
    }

    /**
     * The detail page lists versions without their bodies, newest first, a page at a time. The bug: it read every
     * body and config of every version, so a prompt with many long versions loaded megabytes for a list of numbers.
     */
    @Test
    void versionsOf_returnsSummariesNewestFirstWithPaging() {
        String pid = project("prompt-repo-version-page");
        PromptRow p = prompt(pid, "p", List.of());
        PromptVersionRow v1 = version(p, 1, "{}");
        PromptVersionRow v2 = version(p, 2, "{}");
        PromptVersionRow v4 = version(p, 4, "{}");

        assertEquals(List.of(summary(v4), summary(v2)), repo.versionsOf(pid, p.id(), 2, null));
        assertEquals(List.of(summary(v2), summary(v1)), repo.versionsOf(pid, p.id(), 2, 4));
        assertEquals(List.of(summary(v1)), repo.versionsOf(pid, p.id(), 5, 2));
        assertEquals(List.of(), repo.versionsOf(project("prompt-repo-version-page-other"), p.id(), 5, null));
    }

    private static PromptVersionSummaryRow summary(PromptVersionRow v) {
        return new PromptVersionSummaryRow(
                v.id(), v.version(), v.commitMessage(), v.createdBy(), v.createdVia(), v.createdAt());
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

    /**
     * One level of the folder tree: the child folders of a path with their prompt counts, and the prompts directly in
     * it. The bugs: {@code team/a} also counts the prompts of {@code team/ab}, a prompt two levels down shows as a
     * direct prompt, a folder past the first page of prompts never shows, and a filter narrows the prompts but not the
     * folder counts.
     */
    @Test
    void folderLevel_returnsChildFoldersWithCountsAndDirectPrompts() {
        String pid = project("prompt-repo-level");
        PromptRow labeled = prompt(pid, "team/a/x", List.of());
        repo.upsertLabel(pid, labeled.id(), "staging", version(labeled, 1, "{}").id(), "user-1", T1);
        for (String name : List.of("team/a/y", "team/a/deep/z", "team/ab/w", "team/direct", "root")) {
            version(prompt(pid, name, List.of()), 1, "{}");
        }
        version(prompt(project("prompt-repo-level-other"), "team/a/other", List.of()), 1, "{}");

        PromptListQuery team = query("team", null, null, null);
        assertEquals(
                List.of(new PromptFolderRow("team/a", 3, T1), new PromptFolderRow("team/ab", 1, T1)),
                repo.childFolders(pid, team));
        assertEquals(List.of("team/direct"), names(pid, team.directOnly()));
        assertEquals(1, repo.count(pid, team.directOnly()));

        PromptListQuery root = query(null, null, null, null);
        assertEquals(List.of(new PromptFolderRow("team", 5, T1)), repo.childFolders(pid, root));
        assertEquals(List.of("root"), names(pid, root.directOnly()));

        PromptListQuery teamA = query("team/a", null, null, null);
        assertEquals(List.of(new PromptFolderRow("team/a/deep", 1, T1)), repo.childFolders(pid, teamA));
        assertEquals(List.of("team/a/x", "team/a/y"), names(pid, teamA.directOnly()));

        assertEquals(
                List.of(new PromptFolderRow("team/a", 1, T1)),
                repo.childFolders(pid, query("team", null, "staging", null)));
    }

    /**
     * The newest prompt sets a folder's updated time. The bug: the folder row shows the time of an arbitrary prompt.
     */
    @Test
    void folderLevel_folderUpdatedAtIsItsNewestPrompt() {
        String pid = project("prompt-repo-level-time");
        repo.insertPrompt(new PromptRow(Ids.ulid(), pid, "f/old", PromptType.TEXT, List.of(), 0, "user-1", T1, T1));
        repo.insertPrompt(new PromptRow(Ids.ulid(), pid, "f/new", PromptType.TEXT, List.of(), 0, "user-1", T1, T3));
        repo.insertPrompt(new PromptRow(Ids.ulid(), pid, "f/mid", PromptType.TEXT, List.of(), 0, "user-1", T1, T2));

        assertEquals(List.of(new PromptFolderRow("f", 3, T3)), repo.childFolders(pid, query(null, null, null, null)));
    }

    /**
     * The list's label and tag pickers offer every label and tag of the project, sorted, each once. The bug: the pickers
     * offer only what the rows on screen carry, so at a root that holds only folders nothing can be picked.
     */
    @Test
    void facets_listEveryLabelAndTagOfTheProjectOnce() {
        String pid = project("prompt-repo-facet-lists");
        PromptRow a = prompt(pid, "deep/a", List.of("tier-1", "support"));
        repo.upsertLabel(pid, a.id(), "staging", version(a, 1, "{}").id(), "user-1", T1);
        repo.upsertLabel(pid, a.id(), "production", version(a, 2, "{}").id(), "user-1", T1);
        PromptRow b = prompt(pid, "b", List.of("support"));
        repo.upsertLabel(pid, b.id(), "production", version(b, 1, "{}").id(), "user-1", T1);
        PromptRow other = prompt(project("prompt-repo-facet-lists-other"), "c", List.of("elsewhere"));
        repo.upsertLabel(
                other.projectId(), other.id(), "canary", version(other, 1, "{}").id(), "user-1", T1);

        assertEquals(List.of("production", "staging"), repo.labelNames(pid));
        assertEquals(List.of("support", "tier-1"), repo.tagNames(pid));
    }

    /**
     * The bug: every list page, level read and search gathers every version number of each prompt, which only the
     * public list returns, so a prompt with many versions makes each page read all of them into an array.
     */
    @Test
    void list_countsVersionsAndListsTheirNumbersOnlyWhenAsked() {
        String pid = project("prompt-repo-last");
        PromptRow p = prompt(pid, "p", List.of());
        version(p, 1, "{\"a\": 1}");
        version(p, 3, "{\"a\": 3}");
        version(p, 2, "{\"a\": 2}");

        PromptListRow row = repo.list(pid, query(null, null, null, null), 0, 50).getFirst();
        assertEquals(List.of(), row.versions());
        assertEquals("{\"a\": 3}", row.lastConfigJson());
        assertEquals(3, row.latestVersion());
        assertEquals(3, row.versionCount());

        PromptListRow numbered = repo.list(pid, query(null, null, null, null).withVersionNumbers(), 0, 50)
                .getFirst();
        assertEquals(List.of(1, 2, 3), numbered.versions());
        assertEquals(3, numbered.versionCount());
    }

    /**
     * 0059 widens the CHECK to inner whitespace and still refuses it at either edge, which the Java rule trims. The
     * bugs: the SQL backstop still refuses {@code Support Agent}, or stores an untrimmed name.
     */
    @Test
    void nameWithInnerSpaceIsStored_outerSpaceFailsTheCheck() {
        String pid = project("prompt-repo-spaces");
        assertEquals("Support Agent", prompt(pid, "Support Agent", List.of()).name());
        assertEquals("a\u3000b", prompt(pid, "a\u3000b", List.of()).name());
        assertThrows(DataIntegrityViolationException.class, () -> prompt(pid, " a", List.of()));
        assertThrows(DataIntegrityViolationException.class, () -> prompt(pid, "a\u3000", List.of()));
        assertThrows(DataIntegrityViolationException.class, () -> prompt(pid, "a|b", List.of()));
    }

    /**
     * A folder path is used as given: {@code team /a} sits in {@code "team "}, not in {@code team}. The bug: the path
     * is trimmed like a name, so listing {@code "team "} lists {@code team}'s prompts.
     */
    @Test
    void list_folderWithATrailingSpaceIsItsOwnFolder() {
        String pid = project("prompt-repo-folder-space");
        version(prompt(pid, "team /a", List.of()), 1, "{}");
        version(prompt(pid, "team/b", List.of()), 1, "{}");

        assertEquals(List.of("team /a"), names(pid, query("team ", null, null, null)));
        assertEquals(List.of("team/b"), names(pid, query("team", null, null, null)));
    }

    private List<String> filtered(String pid, String json) {
        PromptListQuery q = query(null, null, null, null)
                .withFilter(PromptListFilter.parse(json).orElseThrow());
        return repo.list(pid, q, 0, 50).stream()
                .map(r -> r.prompt().name() + r.versions() + r.lastConfigJson())
                .toList();
    }

    private PromptVersionRow versionAt(PromptRow p, int n, String configJson, Instant created) {
        PromptVersionRow row = new PromptVersionRow(
                Ids.ulid(),
                p.projectId(),
                p.id(),
                n,
                "\"v" + n + "\"",
                configJson,
                null,
                "user-1",
                CreateVia.UI,
                created);
        repo.insertVersion(row);
        return row;
    }

    /**
     * The filter is true of versions: a prompt is listed when one version matches, with only the matching versions and
     * the newest matching config. The bugs: a condition on one version lists every version, the last config comes from
     * a version that did not match, {@code _} works as a wildcard, and {@code latest} is not treated as a label.
     */
    @Test
    void list_filterKeepsPromptsWithAMatchingVersionAndOnlyThoseVersions() {
        String pid = project("prompt-repo-filter-versions");
        PromptRow bot = prompt(pid, "support_bot", List.of("a", "b"));
        PromptVersionRow v1 = versionAt(bot, 1, "{\"model\": \"small\"}", T1);
        PromptVersionRow v2 = versionAt(bot, 2, "{\"model\": \"big-1\"}", T1);
        versionAt(bot, 3, "{\"model\": \"big-2\", \"tools\": []}", T3);
        repo.upsertLabel(pid, bot.id(), "production", v1.id(), "user-1", T1);
        repo.upsertLabel(pid, bot.id(), "staging", v2.id(), "user-1", T1);
        PromptRow other = prompt(pid, "supportxbot", List.of("a"));
        versionAt(other, 1, "{\"model\": \"small\"}", T1);

        String small = "{\"model\": \"small\"}";
        // jsonb returns keys shortest first, then in byte order.
        String big2 = "{\"model\": \"big-2\", \"tools\": []}";
        assertEquals(List.of("support_bot[2, 3]" + big2), filtered(pid, cfg("starts with", "big")));
        assertEquals(List.of("support_bot[3]" + big2), filtered(pid, cfg("ends with", "-2")));
        assertEquals(
                List.of("support_bot[1]" + small, "supportxbot[1]" + small),
                filtered(pid, cfg("does not contain", "BIG")));
        assertEquals(List.of("support_bot[1]" + small, "supportxbot[1]" + small), filtered(pid, cfg("=", "small")));
        assertEquals(
                List.of("support_bot[3]" + big2),
                filtered(
                        pid,
                        "[{\"type\":\"stringObject\",\"column\":\"config\",\"key\":\"tools\",\"operator\":\"is set\"}]"));
        assertEquals(
                List.of(),
                filtered(
                        pid,
                        "[{\"type\":\"stringObject\",\"column\":\"config\",\"key\":\"model\",\"operator\":\"is not set\"}]"));
        assertEquals(
                List.of("support_bot[2, 3]" + big2),
                filtered(pid, "[{\"type\":\"number\",\"column\":\"version\",\"operator\":\">=\",\"value\":2}]"));
        assertEquals(
                List.of("support_bot[2]{\"model\": \"big-1\"}"),
                filtered(
                        pid,
                        "[{\"type\":\"string\",\"column\":\"id\",\"operator\":\"=\",\"value\":\"" + v2.id() + "\"}]"));
        assertEquals(
                List.of("support_bot[3]" + big2),
                filtered(
                        pid,
                        "[{\"type\":\"datetime\",\"column\":\"createdAt\",\"operator\":\">\",\"value\":\"2026-10-18T10:00:00Z\"}]"));
        assertEquals(List.of("support_bot[1]" + small), filtered(pid, labels("any of", "production")));
        assertEquals(
                List.of("support_bot[3]" + big2, "supportxbot[1]" + small), filtered(pid, labels("any of", "latest")));
        assertEquals(List.of("support_bot[1, 2]{\"model\": \"big-1\"}"), filtered(pid, labels("none of", "latest")));
        assertEquals(List.of(), filtered(pid, labels("all of", "production\",\"latest")));
        assertEquals(List.of("support_bot[2]{\"model\": \"big-1\"}"), filtered(pid, labels("all of", "staging")));
        assertEquals(
                1,
                repo.count(
                        pid,
                        query(null, null, null, null)
                                .withFilter(PromptListFilter.parse(cfg("starts with", "big"))
                                        .orElseThrow())));
    }

    /** Conditions on the prompt itself: name, type, tags and when it last changed. */
    @Test
    void list_filterOnPromptColumns() {
        String pid = project("prompt-repo-filter-prompts");
        version(prompt(pid, "support_bot", List.of("a", "b")), 1, "{}");
        PromptRow other = prompt(pid, "supportxbot", List.of("a"));
        version(other, 1, "{}");
        repo.touchPrompt(pid, other.id(), T3);

        assertEquals(List.of("support_bot[1]{}"), filtered(pid, text("contains", "t_b")));
        assertEquals(List.of("supportxbot[1]{}"), filtered(pid, text("=", "supportxbot")));
        assertEquals(List.of("support_bot[1]{}"), filtered(pid, text("does not contain", "X")));
        assertEquals(List.of("support_bot[1]{}", "supportxbot[1]{}"), filtered(pid, text("starts with", "SUPPORT")));
        assertEquals(List.of("supportxbot[1]{}"), filtered(pid, text("ends with", "xbot")));
        assertEquals(
                List.of("support_bot[1]{}", "supportxbot[1]{}"),
                filtered(pid, "[{\"type\":\"string\",\"column\":\"name\",\"operator\":\"is not empty\"}]"));
        assertEquals(
                List.of("support_bot[1]{}", "supportxbot[1]{}"),
                filtered(
                        pid,
                        "[{\"type\":\"stringOptions\",\"column\":\"type\",\"operator\":\"any of\",\"value\":[\"text\"]}]"));
        assertEquals(
                List.of(),
                filtered(
                        pid,
                        "[{\"type\":\"stringOptions\",\"column\":\"type\",\"operator\":\"none of\",\"value\":[\"text\"]}]"));
        assertEquals(List.of("support_bot[1]{}"), filtered(pid, tags("all of", "a\",\"b")));
        assertEquals(List.of("support_bot[1]{}"), filtered(pid, tags("any of", "b")));
        assertEquals(List.of("supportxbot[1]{}"), filtered(pid, tags("none of", "b")));
        assertEquals(
                List.of("support_bot[1]{}"),
                filtered(
                        pid,
                        "[{\"type\":\"datetime\",\"column\":\"updatedAt\",\"operator\":\"<=\",\"value\":\"2026-10-18T09:12:44.118Z\"}]"));
        assertEquals(
                List.of("supportxbot[1]{}"),
                filtered(
                        pid,
                        text("starts with", "support").replace("]", ",")
                                + "{\"type\":\"datetime\",\"column\":\"updatedAt\",\"operator\":\">=\",\"value\":\"2026-10-18T11:00:00Z\"}]"));
        assertEquals(List.of("support_bot[1]{}", "supportxbot[1]{}"), filtered(pid, "[]"));
    }

    private static String cfg(String operator, String value) {
        return "[{\"type\":\"stringObject\",\"column\":\"config\",\"key\":\"model\",\"operator\":\"" + operator
                + "\",\"value\":\"" + value + "\"}]";
    }

    private static String labels(String operator, String values) {
        return "[{\"type\":\"arrayOptions\",\"column\":\"labels\",\"operator\":\"" + operator + "\",\"value\":[\""
                + values + "\"]}]";
    }

    private static String tags(String operator, String values) {
        return "[{\"type\":\"arrayOptions\",\"column\":\"tags\",\"operator\":\"" + operator + "\",\"value\":[\""
                + values + "\"]}]";
    }

    private static String text(String operator, String value) {
        return "[{\"type\":\"string\",\"column\":\"name\",\"operator\":\"" + operator + "\",\"value\":\"" + value
                + "\"}]";
    }
}
