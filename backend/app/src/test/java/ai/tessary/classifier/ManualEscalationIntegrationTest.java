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
import ai.tessary.classifier.finding.TriageLane;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Layer-2 is a hand-pressed button, and this is the surface behind it.
 *
 * <p>The property under test is a negative one first: nothing escalates on its own. A regression
 * here is silent and expensive: it does not fail a request or corrupt a row, it just quietly
 * starts billing, so the guard is that {@code escalated_at} stays null until somebody asks.
 *
 * <p>Against Postgres because both halves are: the escalate-once marker is a conditional update,
 * and the detector filter is a SQL predicate that has to run before the row limit, so a rail asking
 * for one classifier's leads must not have them pushed off the page by a noisy sibling.
 */
@SpringBootTest
class ManualEscalationIntegrationTest {

    /**
     * A horizon comfortably before anything these fixtures stamp, so a finding written twice reads as
     * one spell still running rather than as a recovery and a re-fire, the production behaviour these
     * tests are about. A test that wants the other arm passes its own.
     */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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

    /** A tool-error blob, so the fixture's classifier and the payload it carries are the same detector. */
    private static final String TOOL_ERROR_EVIDENCE = "{\"measure\":\"tool_error_rate\","
            + "\"bucket\":{\"kind\":\"tool\",\"key\":\"tool:write\"},\"cause_kind\":\"rate_shift\","
            + "\"rate\":{\"ref\":0.0547,\"cur\":0.0047},\"n_ref\":502,\"n_cur\":632,"
            + "\"direction\":\"down\",\"onset_at\":\"2026-08-03T01:00:00Z\",\"counts_basis\":\"onset\"}";

    @Test
    @DisplayName("a written finding is not escalated — the row is where the sweep stops")
    void findingsAreWrittenUnescalated() {
        Project p = project("manual-esc-unescalated");
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned");

        assertNull(
                findings.findById(p.projectId(), id).orElseThrow().escalatedAt(),
                "recording a shift must not hand it to Layer 2 — that is a microVM nobody asked for");
        assertEquals(0, triageJobs(p.projectId()), "and it must not have queued a job either");
    }

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
        // The load-bearing assertion: a cause is triaged once. Two presses buying two repo clones
        // is the exact cost the automatic path was removed for.
        assertEquals(1, triageJobs(p.projectId()), "one cause, one microVM, however many presses");
    }

    /**
     * A finding with no exemplar, only members and witnesses, still escalates. The job carries no
     * trace at all: naming one would decide which instance the agent investigates, and it cannot
     * tell our pick from a draw it made itself, so the payload carries the finding id and the
     * population is paged through MCP.
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
                        Instant.now().minus(QUIET_WINDOW).toString(),
                        Instant.now().toString())
                .findingId();
        String now = Instant.now().toString();
        // Members first, so a reader taking the first row inserted would take one of these.
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
                "[classifier_key, finding_id, verdict_id]",
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

    /**
     * The refusal that survives, and the only state that should still produce one: a finding citing no
     * trace at all. There is nothing for the agent to page and nothing to resolve a deploy from, so this
     * is a 409 rather than a microVM that would boot, find an empty evidence set and rule on nothing.
     */
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

    /**
     * A project with no connected repository gets a triage, and it is the same triage every project
     * gets: triage reads no repository, it audits a claim about traffic, which no source file
     * settles, so the press enqueues and names the one lane there is.
     */
    @Test
    @DisplayName("without a connected repo the button enqueues the same triage as everyone else")
    void analyzeWithoutARepoRunsTheEvidenceOnlyLane() {
        Project p = project("manual-esc-no-repo");
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned");

        BehaviorAnalysisView view = behavior.analyze(p.projectId(), id, null);

        assertEquals(TriageLane.EVIDENCE_ONLY.wire(), view.lane(), "the ruling rests on the evidence");
        assertEquals(1, triageJobs(p.projectId()), "and the job is real, not a reported no-op");
        assertNotNull(
                findings.findById(p.projectId(), id).orElseThrow().escalatedAt(),
                "escalate-once applies here too — a second press must not buy a second ruling");
    }

    /** The other side: a connected repo changes nothing, because triage never opens one. */
    @Test
    @DisplayName("a connected repo does not change the lane — triage reads no repository")
    void analyzeWithARepoRunsTheSameLane() {
        Project p = project("manual-esc-repo-lane");
        seedIntegration(p.projectId());
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned");

        assertEquals(
                TriageLane.EVIDENCE_ONLY.wire(),
                behavior.analyze(p.projectId(), id, null).lane());
    }

    /**
     * Escalating a finding to Layer 2 never depends on anything outside the finding itself: Layer 2
     * asks whether a deviation is legitimate, and it must read the finding's own evidence and
     * nothing else. A bare project, with no pipeline import and no call-site definition behind the
     * bucket, still escalates.
     */
    @Test
    @DisplayName("a project with no pipeline at all can still escalate a finding")
    void escalationDoesNotDependOnAGraderExisting() {
        Project p = project("manual-esc-no-graders");
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned");

        assertEquals(
                0,
                jdbc.sql("SELECT count(*) FROM call_site WHERE project_id = :pid")
                        .param("pid", p.projectId())
                        .query(Long.class)
                        .single(),
                "the premise: this project has no imported pipeline to depend on");

        behavior.analyze(p.projectId(), id, null);

        assertEquals(1, triageJobs(p.projectId()), "the escalation happened anyway");
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
                "duration_drift owns both duration measures and neither cost nor behaviour");
        assertEquals(
                Set.of("cost:" + BUCKET + ":dearer:pinned"),
                causeKeys(p, BuiltInDetector.Kind.COST_DRIFT),
                "cost is the only measure that opens a cost_drift finding — the token measures ride as evidence");
        assertTrue(
                causeKeys(p, BuiltInDetector.Kind.BEHAVIOR_DRIFT).isEmpty(),
                "behaviour drift's causes are its three kinds, none of which is a distribution shift");
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

    private Set<String> causeKeys(Project p, String detector) {
        return behavior.findings(p.projectId(), null, null, detector, false).findings().stream()
                .map(f -> f.causeKey())
                .collect(Collectors.toSet());
    }

    /** Queued Layer-2 runs for this project: the thing an accidental automatic escalation would bill. */
    private long triageJobs(String projectId) {
        return jdbc.sql("SELECT count(*) FROM job WHERE project_id = :pid AND kind = 'triage'")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    private void seedIntegration(String projectId) {
        String now = Instant.now().toString();
        integrations.insert(new GitIntegrationRow(
                Ids.ulid(), projectId, "github", "github.com", "acme", "app", "main", "enc", "sha", now, now));
    }

    private record Project(String projectId, String baselineId) {}

    private Project project(String slug) {
        String projectId = bootstrapGranted(slug).project().id();
        classifiers.seedBuiltIns(projectId);
        String classifierId = signals.findByKey(projectId, BuiltInDetector.Kind.DURATION_DRIFT)
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
                        Instant.now().minus(QUIET_WINDOW).toString(),
                        Instant.now().toString())
                .findingId();
        // What the metric sweep actually writes: a span-grain `member`, and no exemplar. The role was
        // dropped for both drift measures because a member of a shifted population is not an anomaly
        // in it.
        findingEvidence.record(
                p.projectId(),
                id,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.span("trace-member", "span-member")),
                Instant.now().toString());
        return id;
    }

    /**
     * Which classifier a measure files under. The sweep spells the same mapping; a test that hardcoded
     * one key would make the detector filter pass by construction.
     */
    private static String classifierFor(String causeKey) {
        return causeKey.startsWith("cost:") ? BuiltInDetector.Kind.COST_DRIFT : BuiltInDetector.Kind.DURATION_DRIFT;
    }

    /**
     * Bootstrap a tenant whose org has behaviour drift and SOP conformance switched on before its
     * project is created.
     *
     * <p>Two things make this necessary. Both classifiers are off by default, so without a grant
     * these cases would assert the capability default rather than the behaviour they name. And the
     * grant has to precede the project, because project creation is what seeds the built-in
     * classifiers: grant afterwards and the classifier row is never inserted, leaving the test
     * hunting findings from a classifier the project does not have.
     */
    private TenantFixture.Setup bootstrapGranted(String name) {
        return TenantFixture.bootstrap(tenants, name, org -> {
            capabilities.grant(org.id(), Capability.BEHAVIOR_DRIFT);
            capabilities.grant(org.id(), Capability.SOP_CONFORMANCE);
        });
    }
}
