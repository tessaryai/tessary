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
 * The signal definition model and lifecycle against real Postgres: the catalog seeds per project idempotently, and a
 * definition can be enabled and disabled.
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
        // Creating the project is the whole precondition (ClassifierSeedListener).
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
        // Every built-in seeds enabled except Frustration (spends provider credit) and Groundedness (needs a model
        // server). What reaches a project is PartnerCatalogTest's.
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

        // A project seeded by an old catalog: a 'wins' built-in no longer shipped.
        String now = Instant.now().toString();
        signals.insert(new ClassifierRow(
                Ids.ulid(),
                pid,
                "wins",
                "Wins",
                "A clear success moment — praise or an explicit goal completion (retired built-in).",
                "wins",
                null,
                true, // built_in
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

        // An old catalog: only 'frustration', at an old version, as the project's entire state.
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
                true, // built_in
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
        // The version triggers, config_json is the payload: carrying the version without the config would report the
        // current version while scoring on the old one.
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
     * The classifier API through its controller. Catches the wrong project or id, a write the next read does not
     * show, a tuning response echoing the request instead of the clamped value, and a classifier missing from list
     * surfaces.
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
     * Grants {@code frustration} and {@code groundedness} before the project exists, since creation seeds the built-
     * ins.
     */
    private TenantFixture.Setup bootstrapGranted(String name) {
        return TenantFixture.bootstrap(tenants, name, org -> {
            capabilities.grant(org.id(), Capability.FRUSTRATION);
            capabilities.grant(org.id(), Capability.GROUNDEDNESS);
        });
    }
}
