// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.ClassifierRepository;
import ai.tessary.evals.classifier.ClassifierService;
import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.finding.FindingRepository;
import ai.tessary.evals.classifier.finding.FindingRow;
import ai.tessary.evals.classifier.metric.MetricBaselineRepository;
import ai.tessary.evals.classifier.metric.MetricBaselineRow;
import ai.tessary.evals.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.evals.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.evals.classifier.metric.MetricBaselineRow.State;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The triage gate, against the query that enforces it.
 *
 * <p>{@link MetricDriftSourceTest} covers what a detection says; this covers <b>which findings become
 * one at all</b>, and it has to run against Postgres because the gate is a SQL predicate. That is
 * deliberate rather than incidental: the filter has to be applied before the row limit, or a burst of
 * un-triaged leads could push a confirmed regression off the live set — and a row missing from the
 * live set does not merely fail to appear, it reads to {@link CaseReconciler} as a recovery and closes
 * the case.
 *
 * <p>Metric-drift findings are written with no alert budget at all, so this gate is the only thing
 * between that stream and the screen people get paged from.
 */
@SpringBootTest
class MetricDriftCaseGateIntegrationTest {

    /**
     * A horizon comfortably before anything these fixtures stamp, so a finding written twice reads as ONE
     * spell still running rather than as a recovery and a re-fire — the production behaviour these tests
     * are about. A test that wants the other arm passes its own.
     */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    MetricDriftSource source;

    @Autowired
    FindingRepository findings;

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    TenantService tenants;

    private static final String BUCKET = "discover-sales-prospects";

    /** PROGRAM.md §7's blob, trimmed to the fields a case is built from. */
    private static final String EVIDENCE = "{\"measure\":\"turn_duration\",\"bucket\":{\"kind\":\"call_site\","
            + "\"key\":\"" + BUCKET + "\"},\"reference\":\"pinned\",\"w1_log\":0.34,\"ratio\":1.4049,"
            + "\"direction\":\"up\",\"n_ref\":4210,\"n_cur\":1180,"
            + "\"quantiles\":{\"p50\":[2100,2940],\"p95\":[9000,21400]}}";

    @Test
    @DisplayName("only the triaged subset reaches Triage; a lead nobody has ruled on opens nothing")
    void onlyFindingsThatSurvivedTriageBecomeCases() {
        Project p = project("metric-case-gate");

        String lead = shift(p, "turn_duration:" + BUCKET + ":slower:pinned", Instant.now());
        String confirmed = shift(p, "turn_duration:" + BUCKET + ":slower:previous", Instant.now());
        String human = shift(p, "cost:" + BUCKET + ":dearer:pinned", Instant.now());
        String absorbed = shift(p, "tool_duration:tool:search_docs:slower:pinned", Instant.now());

        findings.recordTriage(
                p.projectId(),
                confirmed,
                FindingRow.TriageVerdict.POSITIVE,
                "The retry loop added in 4f2a1c explains it.",
                null,
                now());
        // The verdicts the gate withholds. `negative` and `unclear` both CLOSE the finding, which is a
        // different thing from opening a case: the row stays readable and its cause keeps being counted,
        // so a wrong close is recovered by recurrence rather than by a person noticing.
        findings.recordTriage(
                p.projectId(),
                absorbed,
                FindingRow.TriageVerdict.NEGATIVE,
                "The reference window holds 40 turns.",
                null,
                now());
        // A human pressing "Real deviation" outranks the machine, and carries no machine verdict at all.
        findings.setStatus(p.projectId(), human, FindingRow.Status.BLOCKED, now());

        Set<String> firing = source.detect(p.projectId()).stream()
                .map(d -> d.key().subjectId())
                .collect(Collectors.toSet());

        assertEquals(
                Set.of("turn_duration:" + BUCKET + ":slower:previous", "cost:" + BUCKET + ":dearer:pinned"),
                firing,
                "the machine-confirmed shift and the human-confirmed one, and nothing else");
        assertTrue(
                findings.findById(p.projectId(), lead).isPresent(),
                "the withheld lead is still a finding — it is kept off Triage, not deleted");
    }

    @Test
    @DisplayName("absorbing a confirmed shift drops it from the live set, so its case closes itself")
    void anAbsorbedShiftLeavesTheLiveSet() {
        Project p = project("metric-case-absorb");
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned", Instant.now());
        findings.recordTriage(
                p.projectId(), id, FindingRow.TriageVerdict.POSITIVE, "Explained by 4f2a1c.", null, now());
        assertEquals(1, source.detect(p.projectId()).size());

        // What "Legitimate — absorb" does to the finding. The reference re-pin is FindingService's
        // half and is covered by MetricFindingResolveIntegrationTest; what matters here is that the
        // absorbed cause stops being live, which is how a case closes with nobody touching Triage.
        findings.setStatus(p.projectId(), id, FindingRow.Status.ALLOWLISTED, now());

        assertTrue(source.detect(p.projectId()).isEmpty());
    }

    @Test
    @DisplayName("a shift that stopped firing falls out of the live set once its window horizon lapses")
    void aRecoveredShiftFallsOutOnRecency() {
        Project p = project("metric-case-recovery");
        // Last seen a fortnight ago, against duration_drift's 168-hour window horizon: this bucket has
        // gone longer than its own classifier's longest window without shifting again, which is the only
        // evidence of recovery there is — nothing writes "it came back".
        String stale = now(Duration.ofDays(14));
        String id = shift(
                p, "turn_duration:" + BUCKET + ":slower:pinned", Instant.now().minus(Duration.ofDays(14)));
        findings.recordTriage(
                p.projectId(), id, FindingRow.TriageVerdict.POSITIVE, "Explained by 4f2a1c.", null, stale);

        assertTrue(source.detect(p.projectId()).isEmpty(), "a case whose detection stopped must not stay live");
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

    private record Project(String projectId, String baselineId) {}

    private Project project(String slug) {
        String projectId = TenantFixture.bootstrap(tenants, slug).project().id();
        classifiers.seedBuiltIns(projectId);
        String classifierId = signals.findByKey(projectId, BuiltInDetector.Kind.DURATION_DRIFT)
                .orElseThrow()
                .id();
        String now = now();
        String baselineId = baselines
                .ensure(new MetricBaselineRow(
                        Ids.ulid(),
                        projectId,
                        classifierId,
                        Measure.TURN_DURATION,
                        BucketKind.CALL_SITE,
                        BUCKET,
                        State.ARMED,
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
        return new Project(projectId, baselineId);
    }

    /** One recorded shift on the project's single baseline, stamped at {@code seenAt}. */
    private String shift(Project p, String causeKey, Instant seenAt) {
        return findings.recordShift(
                        Ids.ulid(),
                        p.projectId(),
                        BuiltInDetector.Kind.DURATION_DRIFT,
                        p.baselineId(),
                        causeKey,
                        1180,
                        "pv-deploy-9",
                        BUCKET,
                        EVIDENCE,
                        seenAt.minus(QUIET_WINDOW).toString(),
                        seenAt.toString())
                .findingId();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String now(Duration ago) {
        return Instant.now().minus(ago).toString();
    }
}
