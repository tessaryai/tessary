// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.onboarding.OnboardingController.OnboardingView;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The onboarding read against the real schema, one project walked up the whole ladder. Each rung is a fact the
 * product already keeps, so the bugs are a rung read off the wrong fact (a read-only or revoked key counted as
 * listening, a case a human opened counted as the milestone), a number behind a rung summed wrong, and the
 * ladder failing to report the furthest rung once a later one is reached.
 */
@SpringBootTest
class OnboardingControllerTest {

    private static final String T0 = "2026-01-01T00:00:00Z";
    private static final String FINDING_ONSET = "2026-01-02T00:00:00Z";
    private static final String CASE_OPENED = "2026-01-03T00:00:00Z";

    @Autowired
    OnboardingController controller;

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService keys;

    @Autowired
    SubstrateWriter writer;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    ClassifierRepository classifierRows;

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theLadderReportsTheFurthestRungAndTheNumbersBehindIt() throws InterruptedException {
        TenantFixture.Setup fix = TenantFixture.bootstrap(tenants, "onboarding-ladder");
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        String pid = fix.project().id();
        String user = fix.user().id();

        assertEquals(
                view(OnboardingStage.NOT_CONNECTED, false, null, 0, 0, 0, 0, 0, null, 0, 0, null), read(fix, owner));

        // Neither a read-only key nor a revoked write key can push spans, so neither is listening.
        keys.issue(pid, user, "dashboard", KeyScope.QUERY);
        keys.revoke(
                keys.issue(pid, user, "old exporter", KeyScope.WRITE).token().id());
        assertEquals(OnboardingStage.NOT_CONNECTED, read(fix, owner).stage());

        keys.issue(pid, user, "exporter", KeyScope.WRITE);
        assertEquals(view(OnboardingStage.LISTENING, true, null, 0, 0, 0, 0, 0, null, 0, 0, null), read(fix, owner));

        writer.enqueue(
                pid,
                List.of(new RawEntry(
                        "onb-ladder-root",
                        "agent",
                        "user question",
                        "agent answer",
                        null,
                        Map.of("session.id", "conv-onb-ladder"),
                        null,
                        "onb-ladder",
                        T0,
                        KindNormalizer.AGENT)));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");
        assertEquals(view(OnboardingStage.FITTING, true, T0, 0, 0, 0, 0, 0, null, 0, 0, null), read(fix, owner));

        // Two buckets: one armed with 40 in its open window, one still learning with 25.
        classifiers.seedBuiltIns(pid);
        String classifierId = ClassifierRows.byKey(classifierRows, pid, BuiltInDetector.Kind.DURATION_DRIFT)
                .orElseThrow()
                .id();
        baseline(pid, classifierId, "cs-armed", 40, MetricBaselineRow.State.ARMED);
        baseline(pid, classifierId, "cs-learning", 25, MetricBaselineRow.State.LEARNING);
        assertEquals(view(OnboardingStage.WATCHING, true, T0, 2, 1, 65, 40, 0, null, 0, 0, null), read(fix, owner));

        String findingId = finding(pid);
        assertEquals(
                view(OnboardingStage.FINDING, true, T0, 2, 1, 65, 40, 1, FINDING_ONSET, 0, 0, null), read(fix, owner));

        // A case a person opened is a real case, and not the milestone: the product did not get there unaided.
        openCase(pid, findingId);
        assertEquals(
                view(OnboardingStage.FINDING, true, T0, 2, 1, 65, 40, 1, FINDING_ONSET, 1, 0, null), read(fix, owner));

        jdbc.sql("UPDATE finding SET triaged_at = :now WHERE project_id = :pid AND id = :id")
                .param("now", Instant.now().toString())
                .param("pid", pid)
                .param("id", findingId)
                .update();
        assertEquals(
                view(OnboardingStage.CASE, true, T0, 2, 1, 65, 40, 1, FINDING_ONSET, 1, 1, CASE_OPENED),
                read(fix, owner));
    }

    private OnboardingView read(TenantFixture.Setup fix, TenantContext owner) {
        return Objects.requireNonNull(controller
                .progress(owner, fix.org().slug(), fix.project().slug())
                .data());
    }

    private static OnboardingView view(
            OnboardingStage stage,
            boolean listening,
            @Nullable String traffic,
            long buckets,
            long armed,
            long inFlight,
            long bestWindow,
            long findings,
            @Nullable String firstFindingAt,
            long cases,
            long triaged,
            @Nullable String firstTriagedAt) {
        return new OnboardingView(
                stage,
                listening,
                traffic,
                traffic,
                buckets,
                armed,
                inFlight,
                bestWindow,
                MetricDriftConfig.DEFAULT_MIN_SAMPLE,
                findings,
                firstFindingAt,
                cases,
                triaged,
                firstTriagedAt);
    }

    private void baseline(String pid, String classifierId, String bucket, long count, String state) {
        String now = Instant.now().toString();
        MetricBaselineRow row = baselines.ensure(new MetricBaselineRow(
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
                now));
        baselines.advanceWindow(row.id(), count, now, T0, T0, now, "trace-" + bucket);
        baselines.updateState(row.id(), state, now);
    }

    private String finding(String pid) {
        String id = Ids.ulid();
        String now = Instant.now().toString();
        jdbc.sql("""
                        INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                             status, onset_at, last_seen_at, created_at, updated_at)
                        VALUES (:id, :pid, 'duration_drift', 'turn_duration:cs-armed:up', 'metric_baseline',
                                'baseline-1', 'open', :onset, :onset, :now, :now)
                        """)
                .param("id", id)
                .param("pid", pid)
                .param("onset", FINDING_ONSET)
                .param("now", now)
                .update();
        return id;
    }

    private void openCase(String pid, String findingId) {
        String caseId = Ids.ulid();
        jdbc.sql("""
                        INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id,
                                               subject_label, metric, state, resolved_at, title,
                                               basis, severity, onset_at, opened_at, last_seen_at, updated_at)
                        VALUES (:id, :pid, 1, 'metric_drift', 'metric_baseline', 'baseline-1', 'label',
                                'turn_duration', 'open', NULL, 'title', 'basis', 0.5, :onset, :opened, :opened,
                                :opened)
                        """)
                .param("id", caseId)
                .param("pid", pid)
                .param("onset", FINDING_ONSET)
                .param("opened", CASE_OPENED)
                .update();
        jdbc.sql("UPDATE finding SET case_id = :cid WHERE project_id = :pid AND id = :fid")
                .param("cid", caseId)
                .param("pid", pid)
                .param("fid", findingId)
                .update();
    }
}
