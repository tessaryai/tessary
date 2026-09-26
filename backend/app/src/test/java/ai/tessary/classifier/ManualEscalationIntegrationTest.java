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
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
 */
@SpringBootTest
class ManualEscalationIntegrationTest {

    /** Before anything the fixtures stamp, so a finding written twice reads as one running spell. */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

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

    private record Project(String projectId, String baselineId) {}

    private Project project(String slug) {
        String projectId = TenantFixture.bootstrap(tenants, slug).project().id();
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
        return new Project(projectId, baselineId);
    }

    private String shift(Project p, String causeKey) {
        String id = findings.recordShift(
                        Ids.ulid(),
                        p.projectId(),
                        classifierFor(causeKey),
                        p.baselineId(),
                        causeKey,
                        1180,
                        "pv-deploy-9",
                        BUCKET,
                        EVIDENCE,
                        Instant.now().toString(),
                        Instant.now().minus(QUIET_WINDOW).toString(),
                        Instant.now().toString())
                .findingId();
        // What the metric sweep writes: a span-grain member, no exemplar.
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
