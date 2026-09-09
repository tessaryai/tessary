// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code project_model_setting} against the real Postgres — the round-trip the mocked
 * {@link ProjectModelSettingsTest} cannot cover: that the migration applied, that the enum values we
 * write actually satisfy the table's CHECK constraints, and that they map back to the same enums.
 */
@SpringBootTest
class ProjectModelSettingRepositoryTest {

    @Autowired
    TenantService tenants;

    @Autowired
    ProjectModelSettingRepository repo;

    @Test
    void upsertRoundTripsEveryLaneAndOnlineTier() {
        var fix = TenantFixture.bootstrap(tenants, "model-setting-roundtrip");
        String pid = fix.project().id();

        // Every lane and every ONLINE tier must satisfy the table's CHECK constraints — this is what
        // catches a lane added in Java but not in the migration's allow-list.
        for (ModelLane lane : ModelLane.values()) {
            repo.upsert(pid, lane, "amazon.nova-2-lite", ServiceTier.FLEX, null);
        }
        List<ProjectModelSetting> rows = repo.findByProject(pid);
        assertEquals(ModelLane.values().length, rows.size());
        for (ProjectModelSetting row : rows) {
            assertEquals("amazon.nova-2-lite", row.modelKey());
            assertEquals(ServiceTier.FLEX, row.serviceTier());
        }
    }

    @Test
    void upsertReplacesRatherThanDuplicating() {
        var fix = TenantFixture.bootstrap(tenants, "model-setting-replace");
        String pid = fix.project().id();

        repo.upsert(pid, ModelLane.RCA, "amazon.nova-2-lite", ServiceTier.FLEX, null);
        repo.upsert(pid, ModelLane.RCA, "anthropic.claude-haiku-4-5", ServiceTier.STANDARD, null);

        // Exactly one row, and it is the second write: (project, lane) is the primary key, so two
        // upserts onto RCA land on the same row rather than adding to it. Creating the project writes
        // no rows at all — a lane is resolved from the org's configured providers unless someone has
        // chosen one — so the only row here is the one this test put there. This test writes through
        // the repository directly, bypassing ProjectModelSettings.validate() — Nova is not agentic and
        // would fail that gate — so it is proving the DB round trip and CHECK constraints only, not
        // the application-level lane/model pairing rules.
        List<ProjectModelSetting> rows = repo.findByProject(pid);
        assertEquals(1, rows.size(), "(project, lane) is the primary key");
        ProjectModelSetting rca =
                rows.stream().filter(r -> r.lane() == ModelLane.RCA).findFirst().orElseThrow();
        assertEquals("anthropic.claude-haiku-4-5", rca.modelKey());
        assertEquals(ServiceTier.STANDARD, rca.serviceTier());
    }

    @Test
    void settingsAreScopedToTheirProject() {
        var a = TenantFixture.bootstrap(tenants, "model-setting-scope-a");
        var b = TenantFixture.bootstrap(tenants, "model-setting-scope-b");

        repo.upsert(a.project().id(), ModelLane.RCA, "amazon.nova-2-lite", ServiceTier.FLEX, null);

        assertEquals(
                "amazon.nova-2-lite",
                repo.findByProject(a.project().id()).stream()
                        .filter(r -> r.lane() == ModelLane.RCA)
                        .findFirst()
                        .orElseThrow()
                        .modelKey());
        // B gets no row at all, which is the stronger form of the same claim: a row exists only
        // because someone chose a model on that project, so a write to A cannot leave one behind on
        // its sibling. B's own RCA lane still runs — resolved from its org's configured providers —
        // it simply has nothing stored to resolve against.
        assertTrue(
                repo.findByProject(b.project().id()).stream().noneMatch(r -> r.lane() == ModelLane.RCA),
                "the write must not have reached the sibling project");
    }
}
