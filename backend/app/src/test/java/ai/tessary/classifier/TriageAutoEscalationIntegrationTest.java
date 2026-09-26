// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.finding.TriageAutoEscalator;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.featureflags.FeatureFlags;
import ai.tessary.featureflags.FlagContext;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Automatic Layer-2 escalation: off by default, bounded when on. Both regressions are silent and show up only on the
 * bill, so the assertions count jobs. Against Postgres because the bound is a query: the budget counts {@code job}
 * rows in a rolling window, and eligibility must exclude escalated, human-ruled and exemplar-less findings before the
 * limit.
 */
@SpringBootTest
class TriageAutoEscalationIntegrationTest {

    /** Before anything the fixtures stamp, so a finding written twice reads as one running spell. */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

    /**
     * Stub {@link FeatureFlags}, not {@code CapabilityService}: flags hold no defaults, so the real override-then-
     * default resolution runs and "no override" leaves {@code triage_automatic} at its default of false.
     */
    @MockitoBean
    FeatureFlags flags;

    @Autowired
    TriageAutoEscalator escalator;

    /** The Run analysis button's service: the manual arm both guards leave alone. */
    @Autowired
    FindingService drift;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository findingEvidence;

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    private static final String BUCKET = "discover-sales-prospects";

    private static final String EVIDENCE = "{\"measure\":\"turn_duration\",\"bucket\":{\"kind\":\"call_site\","
            + "\"key\":\"" + BUCKET + "\"},\"reference\":\"pinned\",\"w1_log\":0.34,\"ratio\":1.4049,"
            + "\"direction\":\"up\",\"n_ref\":4210,\"n_cur\":1180,"
            + "\"quantiles\":{\"p50\":[2100,2940],\"p95\":[9000,21400]}}";

    /**
     * Ticks are idempotent through the once-per-look guarantee in {@code FindingService#analyze}, so a 15-minute
     * scheduler never re-spends on a ruled finding.
     */
    @Test
    @DisplayName("further ticks do not re-schedule findings already escalated")
    void repeatedTicksDoNotReEscalate() {
        flagsSilent();
        Project p = project("auto-esc-budget");
        automaticOn(p);
        for (int i = 0; i < 6; i++) {
            shift(p, "turn_duration:" + BUCKET + i + ":slower:pinned", 5);
        }

        escalator.tick();
        long afterFirst = triageJobs(p.projectId());
        escalator.tick();
        escalator.tick();

        assertEquals(6, afterFirst, "the first tick takes every eligible finding");
        assertEquals(6, triageJobs(p.projectId()), "and the next two find nothing left to schedule");
    }

    /**
     * A cause seen once is a coincidence, and a human-ruled cause is settled (a machine opinion on a BLOCKED
     * finding would re-litigate a person's decision). The eligible control beside them keeps the zero honest.
     */
    @Test
    @DisplayName("a finding under the recurrence bar, or ruled by a human, is not escalated automatically")
    void ineligibleFindingsAreSkippedBesideAnEligibleOne() {
        flagsSilent();
        Project p = project("auto-esc-ineligible");
        automaticOn(p);
        shift(p, "turn_duration:" + BUCKET + "-once:slower:pinned", 1);
        String ruled = shift(p, "turn_duration:" + BUCKET + "-ruled:slower:pinned", 5);
        findings.recordHumanRuling(
                p.projectId(),
                ruled,
                FindingRow.TriageVerdict.POSITIVE,
                "A person ruled this a real deviation.",
                Instant.now().toString());
        shift(p, "turn_duration:" + BUCKET + "-eligible:slower:pinned", 5);

        escalator.tick();

        assertEquals(1, triageJobs(p.projectId()), "only the eligible control escalates");
    }

    @Test
    @DisplayName("the flag is per org, so an un-targeted org is untouched by a targeted one's tick")
    void theFlagIsScopedToItsOrg() {
        flagsSilent();
        Project on = project("auto-esc-scope-on");
        Project off = project("auto-esc-scope-off");
        automaticOn(on);
        shift(on, "turn_duration:" + BUCKET + ":slower:pinned", 5);
        shift(off, "turn_duration:" + BUCKET + ":slower:pinned", 5);

        escalator.tick();

        assertTrue(triageJobs(on.projectId()) > 0, "the targeted org escalates");
        assertEquals(0, triageJobs(off.projectId()), "and nobody else does");
    }

    /**
     * No confirmation bar: an escalatable finding escalates on the first tick. The seam's two-store dispatch is
     * covered by {@code FindingServiceMergeTest} and {@code TriageSourceAbsenceTest}.
     */
    @Test
    @DisplayName("an escalatable finding escalates on the first tick")
    void anEscalatableFindingEscalatesOnTheFirstTick() {
        flagsSilent();
        Project p = project("auto-esc-first-tick");
        automaticOn(p);
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned", 5);

        escalator.tick();

        assertEquals(1, triageJobs(p.projectId()), "the finding escalates on the first tick");
        assertNotNull(findings.findById(p.projectId(), id).orElseThrow().escalatedAt());
    }

    /** An empty flag store, which is also what an outage produces. */
    private void flagsSilent() {
        when(flags.override(anyString(), any())).thenReturn(Optional.empty());
    }

    private void automaticOn(Project p) {
        when(flags.override(Capability.TRIAGE_AUTOMATIC.wire(), FlagContext.forOrg(p.orgId())))
                .thenReturn(Optional.of(true));
    }

    private record Project(String orgId, String projectId, String baselineId) {}

    private Project project(String slug) {
        TenantFixture.Setup setup = TenantFixture.bootstrap(tenants, slug);
        // Every case here is a metric-drift finding. {@code triage_automatic} is not granted: it is this class's
        // subject.
        String projectId = setup.project().id();
        classifiers.seedBuiltIns(projectId);
        String classifierId = ClassifierRows.byKey(signals, projectId, BuiltInDetector.Kind.DURATION_DRIFT)
                .orElseThrow()
                .id();
        String now = Instant.now().toString();
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
        return new Project(setup.org().id(), projectId, baselineId);
    }

    private String shift(Project p, String causeKey, long samples) {
        String id = findings.recordShift(
                        Ids.ulid(),
                        p.projectId(),
                        classifierFor(causeKey),
                        p.baselineId(),
                        causeKey,
                        samples,
                        null,
                        BUCKET,
                        EVIDENCE,
                        Instant.now().toString(),
                        Instant.now().minus(QUIET_WINDOW).toString(),
                        Instant.now().toString())
                .findingId();
        // A span-grain member with no exemplar, as the metric sweep writes. Seeding an exemplar would test against
        // evidence the classifier never produces, and an ineligible finding is skipped silently.
        findingEvidence.record(
                p.projectId(),
                id,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.span("trace-member", "span-member")),
                Instant.now().toString());
        return id;
    }

    private long triageJobs(String projectId) {
        return jdbc.sql("SELECT count(*) FROM job WHERE kind = 'triage' AND project_id = :pid")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /** Mirrors the sweep's mapping; hardcoding one key would make the detector filter pass by construction. */
    private static String classifierFor(String causeKey) {
        return causeKey.startsWith("cost:") ? BuiltInDetector.Kind.COST_DRIFT : BuiltInDetector.Kind.DURATION_DRIFT;
    }
}
