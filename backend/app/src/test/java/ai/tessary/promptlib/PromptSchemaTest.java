// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The prompt tables and span link columns against real Postgres: the constraints that back the rules in
 * devdocs/reference/prompt-management.md § Rules and caps, the cascades the writes rely on, and the two partial
 * indexes the usage reads need.
 */
@SpringBootTest
record PromptSchemaTest(TenantService tenants, JdbcClient jdbc) {

    private String prompt(String projectId, String userId, String name) {
        String id = "pr_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO prompt (id, project_id, name, type, created_by) VALUES (?, ?, ?, 'text', ?)")
                .params(id, projectId, name, userId)
                .update();
        return id;
    }

    private String version(String projectId, String promptId, String userId, int number) {
        String id = "pv_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO prompt_version (id, project_id, prompt_id, version, body, created_by, created_via)"
                        + " VALUES (?, ?, ?, ?, '\"Hello\"'::jsonb, ?, 'api')")
                .params(id, projectId, promptId, number, userId)
                .update();
        return id;
    }

    private void label(String projectId, String promptId, String versionId, String userId, String label) {
        jdbc.sql(
                        "INSERT INTO prompt_label (project_id, prompt_id, label, version_id, updated_by) VALUES (?, ?, ?, ?, ?)")
                .params(projectId, promptId, label, versionId, userId)
                .update();
    }

    @Test
    void aNameMayHoldInnerWhitespaceAndFolderSlashes() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-inner-space");
        assertDoesNotThrow(() -> prompt(fix.project().id(), fix.user().id(), "Support Agent/greeting"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a|b", " edge", "edge ", "/lead", "trail/", "dou//ble", ""})
    void theNameBackstopRefusesWhatTheJavaRuleRefuses(String name) {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-bad-name-" + Math.abs(name.hashCode()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> prompt(fix.project().id(), fix.user().id(), name));
    }

    @Test
    void aNameLongerThan255CharactersIsRefused() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-long-name");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> prompt(fix.project().id(), fix.user().id(), "a".repeat(256)));
    }

    @Test
    void aNameIsUniqueInsideAProjectOnly() {
        var one = TenantFixture.bootstrap(tenants, "prompt-schema-unique-one");
        var two = TenantFixture.bootstrap(tenants, "prompt-schema-unique-two");
        prompt(one.project().id(), one.user().id(), "greeting");
        assertDoesNotThrow(() -> prompt(two.project().id(), two.user().id(), "greeting"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> prompt(one.project().id(), one.user().id(), "greeting"));
    }

    @Test
    void versionNumbersStartAtOneAndAreNeverDuplicatedForAPrompt() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-versions");
        String pid = fix.project().id();
        String uid = fix.user().id();
        String p = prompt(pid, uid, "greeting");
        assertThrows(DataIntegrityViolationException.class, () -> version(pid, p, uid, 0));
        version(pid, p, uid, 1);
        assertThrows(DataIntegrityViolationException.class, () -> version(pid, p, uid, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "Production", "has space", "a-label-that-is-longer-than-thirty-six"})
    void aLabelIsLowercaseShortAndNeverLatest(String bad) {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-label-" + Math.abs(bad.hashCode()));
        String pid = fix.project().id();
        String uid = fix.user().id();
        String p = prompt(pid, uid, "greeting");
        String v = version(pid, p, uid, 1);
        assertThrows(DataIntegrityViolationException.class, () -> label(pid, p, v, uid, bad));
    }

    @Test
    void aLabelPointsAtOneVersionAtATime() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-label-one");
        String pid = fix.project().id();
        String uid = fix.user().id();
        String p = prompt(pid, uid, "greeting");
        String v1 = version(pid, p, uid, 1);
        String v2 = version(pid, p, uid, 2);
        label(pid, p, v1, uid, "production");
        assertThrows(DataIntegrityViolationException.class, () -> label(pid, p, v2, uid, "production"));
    }

    @Test
    void deletingAVersionDeletesItsLabelsAndDeletingThePromptDeletesEverything() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-cascade");
        String pid = fix.project().id();
        String uid = fix.user().id();
        String p = prompt(pid, uid, "greeting");
        String v1 = version(pid, p, uid, 1);
        String v2 = version(pid, p, uid, 2);
        label(pid, p, v1, uid, "production");
        label(pid, p, v2, uid, "staging");

        jdbc.sql("DELETE FROM prompt_version WHERE id = ?").param(v1).update();
        assertEquals(
                List.of("staging"),
                jdbc.sql("SELECT label FROM prompt_label WHERE prompt_id = ?")
                        .param(p)
                        .query(String.class)
                        .list());

        jdbc.sql("DELETE FROM prompt WHERE id = ?").param(p).update();
        assertEquals(0, count("prompt_version", p) + count("prompt_label", p));
    }

    private int count(String table, String promptId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE prompt_id = ?")
                .param(promptId)
                .query(Integer.class)
                .single();
    }

    @Test
    void configIsAnObjectTagsAnArrayAndTheCommitMessageAtMost500Characters() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-schema-shapes");
        String pid = fix.project().id();
        String uid = fix.user().id();
        String p = prompt(pid, uid, "greeting");
        String v = version(pid, p, uid, 1);
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE prompt_version SET config = '[]'::jsonb WHERE id = ?")
                        .param(v)
                        .update());
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE prompt SET tags = '{}'::jsonb WHERE id = ?")
                        .param(p)
                        .update());
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE prompt_version SET commit_message = ? WHERE id = ?")
                        .params("m".repeat(501), v)
                        .update());
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE prompt_version SET created_via = 'mcp' WHERE id = ?")
                        .param(v)
                        .update());
    }

    @Test
    void spanCarriesTheTwoNullableLinkColumnsAndNoPromptId() {
        List<String> nullable = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'span'"
                        + " AND column_name IN ('prompt_name', 'prompt_version', 'prompt_id') AND is_nullable = 'YES'"
                        + " ORDER BY column_name")
                .query(String.class)
                .list();
        assertEquals(List.of("prompt_name", "prompt_version"), nullable);
    }

    @Test
    void theUsageIndexesArePartialOnLinkedSpans() {
        String byPrompt = indexDefinition("ix_span_prompt");
        String byWindow = indexDefinition("ix_span_prompt_window");
        assertTrue(byPrompt.contains("(project_id, prompt_name, started_at DESC) INCLUDE (prompt_version)"), byPrompt);
        assertTrue(byPrompt.contains("WHERE (prompt_name IS NOT NULL)"), byPrompt);
        assertTrue(byWindow.contains("(project_id, started_at DESC, prompt_name) INCLUDE (trace_id)"), byWindow);
        assertTrue(byWindow.contains("kind = 'llm'"), byWindow);
        assertTrue(byWindow.contains("is_deleted IS NOT TRUE"), byWindow);
    }

    private String indexDefinition(String name) {
        return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = ?")
                .param(name)
                .query(String.class)
                .optional()
                .orElse("missing index " + name);
    }
}
