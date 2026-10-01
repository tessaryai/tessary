// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.finding.TriageAutoEscalator;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.MetricBaselineRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Layer 2 is a hand-pressed button. First property: nothing escalates on its own; a regression here silently starts
 * billing, so {@code escalated_at} stays null until somebody asks.
 *
 * <p>Against Postgres: the escalate-once marker is a conditional update, and the detector filter runs before the row
 * limit so a noisy sibling cannot push leads off the page.
 *
 * <p>Automatic Layer-2 escalation: off by default, bounded when on. Both regressions are silent and show up only on the
 * bill, so the assertions count jobs. Against Postgres because the bound is a query: the budget counts {@code job}
 * rows in a rolling window, and eligibility must exclude escalated, human-ruled and exemplar-less findings before the
 * limit.
 */
@SpringBootTest
class TriageEscalationIntegrationTest {

    /** Before anything the fixtures stamp, so a finding written twice reads as one running spell. */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

    @Autowired
    TriageAutoEscalator escalator;

    @Autowired
    FindingService behavior;

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
    GitIntegrationRepository integrations;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    JdbcClient jdbc;

    private static final String BUCKET = "discover-sales-prospects";

    private static final String EVIDENCE = "{\"measure\":\"turn_duration\",\"bucket\":{\"kind\":\"call_site\","
            + "\"key\":\"" + BUCKET + "\"},\"reference\":\"pinned\",\"w1_log\":0.34,\"ratio\":1.4049,"
            + "\"direction\":\"up\",\"n_ref\":4210,\"n_cur\":1180,"
            + "\"quantiles\":{\"p50\":[2100,2940],\"p95\":[9000,21400]}}";

    /** A tool-error blob, matching the fixture's classifier. */
    private static final String TOOL_ERROR_EVIDENCE = "{\"measure\":\"tool_error_rate\","
            + "\"bucket\":{\"kind\":\"tool\",\"key\":\"tool:write\"},\"cause_kind\":\"rate_shift\","
            + "\"rate\":{\"ref\":0.0547,\"cur\":0.0047},\"n_ref\":502,\"n_cur\":632,"
            + "\"direction\":\"down\",\"onset_at\":\"2026-08-03T01:00:00Z\",\"counts_basis\":\"onset\"}";

    @Test
    @DisplayName("Run analysis enqueues exactly one microVM, and a second press lands on it")
    void analyzeEscalatesOnceAndIsIdempotent() {
        Project p = project("manual-esc-once");
        seedIntegration(p.projectId());
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned");

        BehaviorAnalysisView first = behavior.analyze(p.projectId(), id, null);
        assertFalse(first.alreadyEscalated(), "the first press is the escalation");
        assertNotNull(
                findings.findById(p.projectId(), id).orElseThrow().escalatedAt(),
                "escalated_at is the escalate-once marker and has to be stamped");
        assertEquals(1, triageJobs(p.projectId()));

        BehaviorAnalysisView second = behavior.analyze(p.projectId(), id, null);
        assertTrue(second.alreadyEscalated(), "a second press must say so rather than report a fresh run");
        assertEquals(first.jobId(), second.jobId(), "and must land on the SAME job");
        // A cause is triaged once: two presses buying two repo clones is the cost the automatic path was removed for.
        assertEquals(1, triageJobs(p.projectId()), "one cause, one microVM, however many presses");
    }

    /**
     * A finding with only members and witnesses still escalates. The job names no trace: picking one would decide
     * which instance the agent investigates; the population is paged through MCP.
     */
    @Test
    @DisplayName("a finding citing witnesses and members escalates, and the job names no trace")
    void analyzeEscalatesOnCitedEvidenceAndCarriesNoTrace() {
        Project p = project("manual-esc-no-exemplar");
        String id = findings.recordShift(
                        Ids.ulid(),
                        p.projectId(),
                        BuiltInDetector.Kind.TOOL_ERROR,
                        p.baselineId(),
                        "tool_error_rate:tool:write:down",
                        688,
                        null,
                        BUCKET,
                        TOOL_ERROR_EVIDENCE,
                        Instant.now().toString(),
                        Instant.now().minus(QUIET_WINDOW).toString(),
                        Instant.now().toString())
                .findingId();
        String now = Instant.now().toString();
        // Members first, so a reader taking the first inserted row would take one.
        findingEvidence.record(
                p.projectId(),
                id,
                FindingEvidenceRow.Role.MEMBER,
                List.of(
                        FindingEvidenceRepository.Ref.span("trace-ok", "span-ok"),
                        FindingEvidenceRepository.Ref.span("trace-also-ok", "span-also-ok")),
                now);
        findingEvidence.record(
                p.projectId(),
                id,
                FindingEvidenceRow.Role.WITNESS,
                List.of(FindingEvidenceRepository.Ref.span("trace-failed", "span-failed")),
                now);

        BehaviorAnalysisView view = behavior.analyze(p.projectId(), id, null);
        assertFalse(view.alreadyEscalated(), "the press escalates rather than refusing the finding");
        assertEquals(1, triageJobs(p.projectId()), "and it queues the one microVM it promised");
        assertEquals(
                "[finding_id]",
                jdbc
                        .sql("SELECT jsonb_object_keys(payload) FROM job WHERE id = :id ORDER BY 1")
                        .param("id", view.jobId())
                        .query(String.class)
                        .list()
                        .stream()
                        .sorted()
                        .toList()
                        .toString(),
                "the payload names the finding and nothing that points at one row of it");
    }

    /** A finding citing no trace at all is a 409, not a microVM that would rule on nothing. */
    @Test
    @DisplayName("a finding citing no population at all is refused rather than booting a microVM")
    void analyzeRefusesAFindingCitingNoEvidence() {
        Project p = project("manual-esc-no-evidence");
        String id = findings.recordShift(
                        Ids.ulid(),
                        p.projectId(),
                        BuiltInDetector.Kind.DURATION_DRIFT,
                        p.baselineId(),
                        "turn_duration:" + BUCKET + ":slower:pinned",
                        1180,
                        null,
                        BUCKET,
                        EVIDENCE,
                        Instant.now().toString(),
                        Instant.now().minus(QUIET_WINDOW).toString(),
                        Instant.now().toString())
                .findingId();

        TessaryException refused =
                assertThrows(TessaryException.class, () -> behavior.analyze(p.projectId(), id, null));
        assertEquals(ClassifierError.FINDING_HAS_NO_EVIDENCE, refused.error());
        assertEquals(0, triageJobs(p.projectId()), "and it must not have queued anything on the way out");
        assertNull(
                findings.findById(p.projectId(), id).orElseThrow().escalatedAt(),
                "nor stamped the escalate-once marker on a finding that was never escalated");
    }

    @Test
    @DisplayName("the rail's detector filter returns one classifier's findings and no sibling's")
    void detectorFilterScopesToOneClassifier() {
        Project p = project("manual-esc-filter");
        shift(p, "turn_duration:" + BUCKET + ":slower:pinned");
        shift(p, "tool_duration:tool:search_docs:slower:pinned");
        shift(p, "cost:" + BUCKET + ":dearer:pinned");

        assertEquals(
                Set.of("turn_duration:" + BUCKET + ":slower:pinned", "tool_duration:tool:search_docs:slower:pinned"),
                causeKeys(p, BuiltInDetector.Kind.DURATION_DRIFT),
                "duration_drift owns both duration measures and not cost");
        assertEquals(
                Set.of("cost:" + BUCKET + ":dearer:pinned"),
                causeKeys(p, BuiltInDetector.Kind.COST_DRIFT),
                "cost is the only measure that opens a cost_drift finding — the token measures ride as evidence");
    }

    /**
     * Ticks are idempotent through the once-per-look guarantee in {@code FindingService#analyze}, so a 15-minute
     * scheduler never re-spends on a ruled finding.
     */
    @Test
    @DisplayName("further ticks do not re-schedule findings already escalated")
    void repeatedTicksDoNotReEscalate() {
        Project p = project("auto-esc-budget");
        capabilities.grant(p.orgId(), Capability.TRIAGE_AUTOMATIC);
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
        Project p = project("auto-esc-ineligible");
        capabilities.grant(p.orgId(), Capability.TRIAGE_AUTOMATIC);
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
        Project on = project("auto-esc-scope-on");
        Project off = project("auto-esc-scope-off");
        capabilities.grant(on.orgId(), Capability.TRIAGE_AUTOMATIC);
        shift(on, "turn_duration:" + BUCKET + ":slower:pinned", 5);
        shift(off, "turn_duration:" + BUCKET + ":slower:pinned", 5);

        escalator.tick();

        assertTrue(triageJobs(on.projectId()) > 0, "the targeted org escalates");
        assertEquals(0, triageJobs(off.projectId()), "and nobody else does");
    }

    /**
     * No confirmation bar: an escalatable finding escalates on the first tick. The seam's two-store dispatch is
     * covered by {@code FindingServiceTest} and {@code BehaviorTriageWorkerTest}.
     */
    @Test
    @DisplayName("an escalatable finding escalates on the first tick")
    void anEscalatableFindingEscalatesOnTheFirstTick() {
        Project p = project("auto-esc-first-tick");
        capabilities.grant(p.orgId(), Capability.TRIAGE_AUTOMATIC);
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned", 5);

        escalator.tick();

        assertEquals(1, triageJobs(p.projectId()), "the finding escalates on the first tick");
        assertNotNull(findings.findById(p.projectId(), id).orElseThrow().escalatedAt());
    }

    private Set<String> causeKeys(Project p, String detector) {
        return behavior.findings(p.projectId(), null, null, detector, false).findings().stream()
                .map(f -> f.causeKey())
                .collect(Collectors.toSet());
    }

    /** Queued Layer-2 runs: what an accidental escalation would bill. */
    private long triageJobs(String projectId) {
        return jdbc.sql("SELECT count(*) FROM job WHERE project_id = :pid AND kind = 'triage'")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    private void seedIntegration(String projectId) {
        String now = Instant.now().toString();
        integrations.insert(new GitIntegrationRow(
                Ids.ulid(), projectId, "github", "github.com", "acme", "app", "main", "enc", now, now));
    }

    private record Project(String orgId, String projectId, String baselineId) {}

    private Project project(String slug) {
        TenantFixture.Setup setup = TenantFixture.bootstrap(tenants, slug);
        String projectId = setup.project().id();
        classifiers.seedBuiltIns(projectId);
        String classifierId = ClassifierRows.byKey(signals, projectId, BuiltInDetector.Kind.DURATION_DRIFT)
                .orElseThrow()
                .id();
        String baselineId = baselines
                .ensure(MetricBaselineRows.fresh(projectId, classifierId, Measure.TURN_DURATION, BUCKET, State.ARMED))
                .id();
        return new Project(setup.org().id(), projectId, baselineId);
    }

    private String shift(Project p, String causeKey) {
        return shift(p, causeKey, 1180, "pv-deploy-9");
    }

    private String shift(Project p, String causeKey, long samples) {
        return shift(p, causeKey, samples, null);
    }

    private String shift(Project p, String causeKey, long samples, @Nullable String projectVersionId) {
        String id = findings.recordShift(
                        Ids.ulid(),
                        p.projectId(),
                        classifierFor(causeKey),
                        p.baselineId(),
                        causeKey,
                        samples,
                        projectVersionId,
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

    /** Mirrors the sweep's mapping; hardcoding one key would make the detector filter pass by construction. */
    private static String classifierFor(String causeKey) {
        return causeKey.startsWith("cost:") ? BuiltInDetector.Kind.COST_DRIFT : BuiltInDetector.Kind.DURATION_DRIFT;
    }
}
