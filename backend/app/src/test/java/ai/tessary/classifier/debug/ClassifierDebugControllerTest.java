// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.classifier.debug.ClassifierDebugDtos.ClassifierDebugView;
import ai.tessary.classifier.debug.ClassifierDebugDtos.MetricBaselineView;
import ai.tessary.classifier.debug.ClassifierDebugDtos.SketchSummary;
import ai.tessary.classifier.debug.ClassifierDebugDtos.SweepView;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricControl;
import ai.tessary.classifier.metric.MetricHistogram;
import ai.tessary.classifier.metric.MetricTokens;
import ai.tessary.classifier.metric.MetricWorkload;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The classifier debug read against the real schema. The bugs are a classifier filed under the wrong execution
 * family, a baseline view built from the wrong slot (the retired previous window rather than the control ring's
 * newest day), a sketch from an older format failing the whole view, and a classifier id from another project
 * resolving through this one's path.
 */
@SpringBootTest
class ClassifierDebugControllerTest {

    private static final String T0 = "2026-01-01T00:00:00Z";
    private static final MetricHistogram.Grid GRID = MetricHistogram.Grid.duration();

    @Autowired
    ClassifierDebugController controller;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    ClassifierRepository rows;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    JdbcClient jdbc;

    /** Each built-in lands in its execution tier, and only the metric-drift family carries baselines at all. */
    @Test
    void eachBuiltInIsFiledUnderItsExecutionFamily() {
        TenantFixture.Setup fix = setup("classifier-debug-families");
        Map<String, String> families = Map.of(
                Kind.GROUNDEDNESS, "encoder",
                Kind.FRUSTRATION, "decision",
                Kind.DURATION_DRIFT, "metric_drift",
                Kind.COST_DRIFT, "metric_drift",
                Kind.SECRET_LEAK, "deterministic");

        families.forEach((detector, family) -> {
            ClassifierDebugView view = debug(fix, classifier(fix, detector).id());
            assertEquals(family, view.family(), detector);
            if ("metric_drift".equals(family)) {
                assertEquals(List.of(), view.metricBaselines(), detector + ": this family, nothing fitted yet");
            } else {
                assertNull(view.metricBaselines(), detector + ": not this family, which is not an empty list");
            }
            assertEquals(SweepView.neverSwept(), view.sweep(), detector + " has never been enqueued");
        });
    }

    /**
     * A baseline is shown against the control ring's newest day, with the classifier's own floor; a pinned
     * sketch in a format this build cannot read is shown as absent instead of failing the view.
     */
    @Test
    void aMetricBaselineShowsItsWindowsAndAnUnreadableSketchIsAbsent() {
        TenantFixture.Setup fix = setup("classifier-debug-metric");
        String pid = fix.project().id();
        ClassifierRow drift = classifier(fix, Kind.DURATION_DRIFT);
        jdbc.sql("UPDATE classifier SET config_json = :config WHERE id = :id")
                .param("config", "{\"w1_floor\": 0.25}")
                .param("id", drift.id())
                .update();
        jobs.enqueue(pid, drift.id(), 0);
        ClassifierJobRow job = jobs.listByProject(pid).stream()
                .filter(j -> j.classifierId().equals(drift.id()))
                .findFirst()
                .orElseThrow();

        String now = Instant.now().toString();
        String watched = baseline(pid, drift.id(), "cs-watched");
        baseline(pid, drift.id(), "cs-quiet");
        MetricControl control = MetricControl.empty()
                .fold(
                        GRID,
                        "2025-12-31",
                        window(50),
                        new MetricWorkload(MetricWorkload.grid(GRID.bins())),
                        new MetricTokens(MetricTokens.grid(GRID.bins())));
        baselines.closeWindow(watched, control.toJson(), now);
        baselines.updateCurrentSketch(watched, window(10).toJson(), null, null, null, now);
        baselines.advanceWindow(watched, 10, now, T0, T0, now, "trace-last");
        baselines.repin(watched, "{\"kind\":\"tdigest\"}", null, null, null, T0, null, now);

        ClassifierDebugView view = debug(fix, drift.id());

        String gridId = GRID.id();
        assertEquals(
                new ClassifierDebugView(
                        drift.id(),
                        Kind.DURATION_DRIFT,
                        "metric_drift",
                        new SweepView(
                                job.status(),
                                job.attempts(),
                                job.lastError(),
                                job.cursorAt(),
                                job.cursorId(),
                                job.leaseOwner(),
                                job.leaseExpiresAt(),
                                job.updatedAt()),
                        List.of(
                                new MetricBaselineView(
                                        MetricBaselineRow.Measure.TURN_DURATION,
                                        MetricBaselineRow.BucketKind.CALL_SITE,
                                        "cs-watched",
                                        MetricBaselineRow.State.LEARNING,
                                        0.25,
                                        10,
                                        T0,
                                        T0,
                                        T0,
                                        null,
                                        new SketchSummary(50, gridId),
                                        new SketchSummary(10, gridId)),
                                new MetricBaselineView(
                                        MetricBaselineRow.Measure.TURN_DURATION,
                                        MetricBaselineRow.BucketKind.CALL_SITE,
                                        "cs-quiet",
                                        MetricBaselineRow.State.LEARNING,
                                        0.25,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))),
                view);
    }

    /** A classifier id from another project does not resolve through this project's path. */
    @Test
    void anotherProjectsClassifierIsNotFound() {
        TenantFixture.Setup mine = setup("classifier-debug-mine");
        TenantFixture.Setup theirs = setup("classifier-debug-theirs");
        String theirClassifier = classifier(theirs, Kind.DURATION_DRIFT).id();

        assertThrows(TessaryException.class, () -> debug(mine, theirClassifier));
    }

    private TenantFixture.Setup setup(String name) {
        TenantFixture.Setup fix = TenantFixture.bootstrap(tenants, name, org -> {
            for (Capability c : List.of(Capability.GROUNDEDNESS, Capability.FRUSTRATION, Capability.SECRET_LEAK)) {
                capabilities.grant(org.id(), c);
            }
        });
        classifiers.seedBuiltIns(fix.project().id());
        return fix;
    }

    private ClassifierRow classifier(TenantFixture.Setup fix, String key) {
        return ClassifierRows.byKey(rows, fix.project().id(), key).orElseThrow(() -> new AssertionError(key));
    }

    private ClassifierDebugView debug(TenantFixture.Setup fix, String classifierId) {
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        ClassifierDebugView view = controller
                .get(owner, fix.org().slug(), fix.project().slug(), classifierId)
                .data();
        assertNotNull(view);
        return Objects.requireNonNull(view);
    }

    private String baseline(String pid, String classifierId, String bucket) {
        String now = Instant.now().toString();
        return baselines
                .ensure(new MetricBaselineRow(
                        Ids.ulid(),
                        pid,
                        classifierId,
                        MetricBaselineRow.Measure.TURN_DURATION,
                        MetricBaselineRow.BucketKind.CALL_SITE,
                        bucket,
                        MetricBaselineRow.State.LEARNING,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        now,
                        now))
                .id();
    }

    private static MetricHistogram window(int n) {
        MetricHistogram h = new MetricHistogram(GRID);
        for (int i = 0; i < n; i++) h.add(Math.log(2_000));
        return h;
    }
}
