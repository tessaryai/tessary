// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.web.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance for the signal <b>definition</b> model + lifecycle: the built-in catalog seeds
 * per project (idempotently), and a definition can be enabled/disabled through its lifecycle. Run
 * against the real pgvector Postgres (Testcontainers), so the signal schema is applied for real.
 */
@SpringBootTest
class ClassifierDefinitionIntegrationTest {

    @Autowired
    ClassifierService service;

    @Autowired
    ClassifierController controller;

    @Autowired
    BuiltInClassifierCatalog catalog;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void seedsBuiltInsOnProjectCreationIdempotently() {
        // No generation run, no repo, no graders: just a project. Auto-classification reads traces,
        // so creating the project is the whole precondition (ClassifierSeedListener).
        String pid = bootstrapGranted("signal-seed").project().id();

        var defs = service.list(pid);
        assertEquals(
                7,
                defs.size(),
                "all built-ins seed on project creation (incl. all three fitting-tier ones: duration "
                        + "drift, cost drift and tool errors)");

        assertEquals(0, service.seedBuiltIns(pid), "re-seeding is a no-op (idempotent)");
        assertEquals(7, service.list(pid).size(), "all built-ins are listable");
        assertTrue(defs.stream().allMatch(ClassifierRow::builtIn), "all seeded signals are marked built_in");
        // Every built-in seeds enabled except Frustration, whose sweep spends the org's own provider
        // credit, and Groundedness, which needs a model server set up first, so a person turns each on.
        // Otherwise whether a classifier runs for an org is a capability-flag decision, not something the
        // seeded row encodes. What reaches a project at all is asserted in PartnerCatalogTest.
        for (ClassifierRow def : defs) {
            assertEquals(
                    !Set.of("frustration", "groundedness").contains(def.classifierKey()),
                    def.enabled(),
                    def.classifierKey() + " seeds with the wrong switch");
        }
    }

    @Test
    void resyncDisablesBuiltInsThatLeftTheCatalog() {
        Project project = bootstrapGranted("signal-retire").project();
        String pid = project.id();

        // Mimic a project seeded by the OLD catalog: a 'wins' built-in row that is no longer shipped.
        String now = Instant.now().toString();
        signals.insert(new ClassifierRow(
                Ids.ulid(),
                pid,
                "wins",
                "Wins",
                "A clear success moment — praise or an explicit goal completion (retired built-in).",
                "wins",
                null,
                true, // built_in, exactly as the old catalog seeded it
                1,
                true, // enabled: the zombie state this test retires
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));

        service.resyncBuiltIns(project);

        ClassifierRow wins = ClassifierRows.byKey(signals, pid, "wins").orElseThrow();
        assertFalse(wins.enabled(), "a built-in whose key left the catalog is disabled on resync");
        assertTrue(wins.builtIn(), "the retired row keeps its built_in marker");
        assertTrue(
                service.list(pid).stream().anyMatch(s -> "wins".equals(s.classifierKey())),
                "the retired built-in stays listed — history is kept, never deleted");
        assertFalse(
                signals.listEnabled(pid).stream().anyMatch(s -> "wins".equals(s.classifierKey())),
                "the retired built-in drops out of the enabled set the worker enqueues");

        service.resyncBuiltIns(project);
        assertFalse(
                ClassifierRows.byKey(signals, pid, "wins").orElseThrow().enabled(),
                "retirement is idempotent across heartbeats");
    }

    @Test
    void resyncInsertsCatalogAdditionsIntoSeededProjects() {
        Project project = bootstrapGranted("signal-catalog-add").project();
        String pid = project.id();

        // Mimic a project seeded by an OLD catalog: only 'frustration' exists, at an old version;
        // the catalog has since grown (e.g. the groundedness built-in) and bumped versions. Clear the
        // seed-on-create catalog first so the old-catalog row is the project's entire starting state.
        jdbc.sql("DELETE FROM classifier WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        String now = Instant.now().toString();
        signals.insert(new ClassifierRow(
                Ids.ulid(),
                pid,
                "frustration",
                "Frustration",
                "User frustration in a turn (old-catalog definition).",
                BuiltInDetector.Kind.FRUSTRATION,
                null,
                true, // built_in: this project WAS seeded, by an older catalog
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));

        service.resyncBuiltIns(project);

        var defs = service.list(pid);
        assertEquals(7, defs.size(), "resync inserts the catalog built-ins the old seed never had");
        assertTrue(
                ClassifierRows.byKey(signals, pid, "groundedness").isPresent(),
                "a built-in added to the catalog after seeding reaches the already-seeded project");
        assertTrue(defs.stream().allMatch(ClassifierRow::builtIn), "everything inserted is marked built_in");
        assertEquals(
                catalog.builtIns().stream()
                        .filter(b -> "frustration".equals(b.classifierKey()))
                        .findFirst()
                        .orElseThrow()
                        .version(),
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow().version(),
                "the pre-existing old-version row is re-synced to the current catalog version");
        // The version is only the TRIGGER; config_json is the payload: the operating band and the
        // context policy both live there. The old row above stores a null config, so if resync carried
        // the version across without the config, a seeded project would keep scoring on whatever it was
        // seeded with while REPORTING the current version, a silent no-op that reads as shipped.
        assertEquals(
                catalog.builtIns().stream()
                        .filter(b -> "frustration".equals(b.classifierKey()))
                        .findFirst()
                        .orElseThrow()
                        .defaultConfigJson(),
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow().configJson(),
                "resync carries the catalog's config_json, not just its version, onto the seeded row");

        service.resyncBuiltIns(project);
        assertEquals(7, service.list(pid).size(), "resync stays idempotent across heartbeats");
    }

    /**
     * The classifier API, driven through its controller against a real tenant. Catches an endpoint that reads
     * or writes under the wrong project or classifier id, a write whose change the next read does not show
     * (enabled, mode, the tuning dial), a tuning response that echoes the request instead of the clamped
     * value in effect, and a read that drops a classifier from the list-shaped surfaces (health, daily volume).
     */
    @Test
    void theClassifierApiReadsBackWhatItWrites() {
        TenantFixture.Setup fix = bootstrapGranted("classifier-api");
        TenantContext ctx = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        String org = fix.org().slug();
        String proj = fix.project().slug();
        String pid = fix.project().id();
        ClassifierRow secretLeak =
                ClassifierRows.byKey(signals, pid, "secret_leak").orElseThrow();
        ClassifierRow costDrift =
                ClassifierRows.byKey(signals, pid, "cost_drift").orElseThrow();
        ClassifierRow frustration =
                ClassifierRows.byKey(signals, pid, "frustration").orElseThrow();

        Set<String> ids = body(controller.list(ctx, org, proj)).stream()
                .map(ClassifierDtos.ClassifierView::id)
                .collect(Collectors.toSet());
        assertEquals(7, ids.size());
        assertEquals(
                ids,
                body(controller.health(ctx, org, proj)).stream()
                        .map(ClassifierDtos.ClassifierHealthView::classifierId)
                        .collect(Collectors.toSet()),
                "every classifier reports a health row, enqueued or not");
        assertEquals(
                "secret_leak",
                body(controller.get(ctx, org, proj, secretLeak.id())).classifierKey());

        assertFalse(body(controller.setEnabled(
                        ctx, org, proj, secretLeak.id(), new ClassifierDtos.SetEnabledRequest(false)))
                .enabled());
        assertEquals(
                ClassifierRow.Mode.TRACKING,
                body(controller.setMode(
                                ctx,
                                org,
                                proj,
                                secretLeak.id(),
                                new ClassifierDtos.SetModeRequest(ClassifierRow.Mode.TRACKING)))
                        .mode());
        ClassifierDtos.ClassifierView after = body(controller.get(ctx, org, proj, secretLeak.id()));
        assertFalse(after.enabled());
        assertEquals(ClassifierRow.Mode.TRACKING, after.mode());
        assertEquals(
                new ClassifierDtos.ClassifierMetricsView(secretLeak.id(), ClassifierRow.Mode.TRACKING, 0, 0, 0),
                body(controller.metrics(ctx, org, proj, secretLeak.id())));
        assertEquals(List.of(), body(controller.events(ctx, org, proj)));
        assertEquals(
                List.of(),
                body(controller.eventsForClassifier(ctx, org, proj, secretLeak.id(), ClassifierRow.Mode.TRACKING, 10)));
        toolCall(pid, "search", "Timeout");
        toolCall(pid, "search", null);
        toolCall(pid, "lookup", null);
        assertEquals(
                List.of(
                        new ClassifierDtos.ToolErrorRateView("search", 2, 1, 0.5),
                        new ClassifierDtos.ToolErrorRateView("lookup", 1, 0, 0.0)),
                body(controller.toolErrorRates(ctx, org, proj, secretLeak.id())),
                "worst first, failed over total per tool");

        assertEquals(
                new ClassifierDtos.TuningView(500, 24, 100, 0.139, null),
                body(controller.getTuning(ctx, org, proj, costDrift.id())));
        assertEquals(
                new ClassifierDtos.TuningView(2_000, 48, 30, 0.2, null),
                body(controller.setTuning(
                        ctx, org, proj, costDrift.id(), new ClassifierDtos.SetTuningRequest(2_000, 48, 5, 0.2))),
                "min_sample is clamped to 30 and the response says so");
        assertEquals(
                new ClassifierDtos.TuningView(2_000, 48, 30, 0.2, null),
                body(controller.getTuning(ctx, org, proj, costDrift.id())));

        assertEquals(
                List.of(),
                body(controller.getFrustrationTuning(ctx, org, proj, frustration.id()))
                        .callSites());

        ClassifierDtos.ClassifierDailyVolumeView volume = body(controller.dailyMetrics(ctx, org, proj, 3));
        assertEquals(3, volume.days().size());
        assertEquals(List.of(0L, 0L, 0L), volume.traceTotals());
        assertEquals(
                ids,
                volume.classifiers().stream()
                        .map(ClassifierDtos.ClassifierDailyCountsView::classifierId)
                        .collect(Collectors.toSet()));
        assertTrue(volume.classifiers().stream().allMatch(c -> c.counts().equals(List.of(0L, 0L, 0L))));
    }

    private void toolCall(String pid, String name, @org.jspecify.annotations.Nullable String errorType) {
        jdbc.sql("""
                        INSERT INTO tool_call (id, project_id, name, error_type, is_error, trace_id, span_id,
                                               started_at, created_at, event_ts)
                        VALUES (:id, :pid, :name, :err, :isErr, :tid, :sid, now(), now(), now())
                        """)
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("name", name)
                .param("err", errorType)
                .param("isErr", errorType != null)
                .param("tid", Ids.ulid())
                .param("sid", Ids.ulid())
                .update();
    }

    private static <T> T body(ApiResponse<T> response) {
        return Objects.requireNonNull(response.data());
    }

    /**
     * Bootstrap a tenant whose org has the capability-gated classifiers switched on before its
     * project is created.
     *
     * <p>Two things make this necessary. {@code frustration} and {@code groundedness} are granted so
     * the set does not depend on which edition's default it has. And the grant has to
     * precede the project, because project creation is what seeds the built-in classifiers: grant
     * afterwards and the classifier row is never inserted, leaving the test hunting findings from a
     * classifier the project doesn't have.
     */
    private TenantFixture.Setup bootstrapGranted(String name) {
        return TenantFixture.bootstrap(tenants, name, org -> {
            capabilities.grant(org.id(), Capability.FRUSTRATION);
            capabilities.grant(org.id(), Capability.GROUNDEDNESS);
        });
    }
}
