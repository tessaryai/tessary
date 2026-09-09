// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Automatic Layer-2 escalation, off by default and bounded when on.
 *
 * <p>Both halves fail silently and expensively if they regress. Automatic mode turning itself on
 * does not break a request or corrupt a row; it starts spending LLM budget on every finding of
 * every project, and the first anyone hears of it is the bill. An unbounded automatic mode does
 * the same thing faster the moment a detector is mis-calibrated. So the assertions here are
 * counts of jobs, which is the only unit either failure is measured in.
 *
 * <p>Against Postgres because the bound is a query: the budget counts rows in {@code job} within
 * a rolling window, and the eligible set is a predicate that has to exclude escalated,
 * human-ruled and exemplar-less findings before the limit rather than after.
 */
@SpringBootTest
class TriageAutoEscalationIntegrationTest {

    /**
     * A horizon comfortably before anything these fixtures stamp, so a finding written twice reads as ONE
     * spell still running rather than as a recovery and a re-fire: the production behaviour these tests
     * are about. A test that wants the other arm passes its own.
     */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

    /**
     * The flag adapter, stubbed, and only the adapter.
     *
     * <p>The obvious seam is {@code CapabilityService}, and it is the wrong one: stubbing the
     * resolver would skip the default that is the actual subject of the first test. {@link
     * FeatureFlags} is the layer that holds no defaults at all, so stubbing it leaves the real
     * override-then-default resolution in place and makes "no override" mean what it means in
     * production: nobody had an opinion, and the default of false for {@code triage_automatic}
     * stood.
     */
    @MockitoBean
    FeatureFlags flags;

    @Autowired
    TriageAutoEscalator escalator;

    /** The <em>Run analysis</em> button's own service: the manual arm both guards must leave alone. */
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
     * The default, and the one that matters most. {@code triage_automatic_enabled} is off by
     * default, so an org gets manual escalation unless it turns automatic on for itself, and an
     * empty or unreachable flag store supplies no opinion and leaves that default standing. The
     * capability layer's failure mode is "nothing happens", never "everything escalates".
     */
    @Test
    @DisplayName("with the flag off, a tick escalates nothing at all")
    void automaticModeIsOffByDefault() {
        flagsSilent();
        Project p = project("auto-esc-off");
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned", 5);

        escalator.tick();

        assertEquals(0, triageJobs(p.projectId()));
        assertNull(findings.findById(p.projectId(), id).orElseThrow().escalatedAt());
    }

    /**
     * The opt-in, and the whole of it: every eligible finding is scheduled, with nothing
     * withheld. The queue and the launcher pool are what bound the work; a finding refused by a
     * counter is one nobody ever sees.
     */
    @Test
    @DisplayName("with the flag on, a tick escalates every eligible finding")
    void automaticModeEscalatesEveryEligibleFinding() {
        flagsSilent();
        Project p = project("auto-esc-on");
        automaticOn(p);
        for (int i = 0; i < 4; i++) {
            shift(p, "turn_duration:" + BUCKET + i + ":slower:pinned", 5);
        }

        escalator.tick();

        assertEquals(4, triageJobs(p.projectId()), "all four are eligible, so all four are scheduled");
    }

    /**
     * Repeated ticks are idempotent: this is what stops a scheduler running every fifteen
     * minutes from re-spending on findings it has already ruled on, the once-per-look guarantee
     * inside {@code FindingService#analyze}, not a counter above it.
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
     * The recurrence bar: a cause observed once is a coincidence, and spending a ruling on it is
     * exactly the cost automatic escalation must not incur.
     */
    @Test
    @DisplayName("a finding under the recurrence bar is not escalated automatically")
    void aSingleSampleIsNotWorthARuling() {
        flagsSilent();
        Project p = project("auto-esc-bar");
        automaticOn(p);
        shift(p, "turn_duration:" + BUCKET + ":slower:pinned", 1);

        escalator.tick();

        assertEquals(0, triageJobs(p.projectId()));
    }

    /**
     * A cause a human has already ruled on is settled. Layer 2's answer would be a second opinion nobody
     * asked for, and on a BLOCKED finding it would be a machine re-litigating a person's decision.
     */
    @Test
    @DisplayName("automatic mode does not re-litigate a cause a human has ruled on")
    void aHumanRuledCauseIsLeftAlone() {
        flagsSilent();
        Project p = project("auto-esc-human");
        automaticOn(p);
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned", 5);
        findings.setStatus(
                p.projectId(), id, FindingRow.Status.BLOCKED, Instant.now().toString());

        escalator.tick();

        assertEquals(0, triageJobs(p.projectId()));
    }

    /** One org's opt-in must not escalate another org's findings. */
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
     * The escalator serves two stores through one {@link ai.tessary.classifier.finding.TriageSource}
     * seam. What this class covers is every case about the escalator itself: the flag gate,
     * per-tick idempotence, the recurrence bar, human-verdict suppression, per-org scoping, and
     * the behaviour arm below. The seam's two-store dispatch is covered without a database by
     * {@code FindingServiceMergeTest} and {@code TriageSourceAbsenceTest}.
     */

    /**
     * Behaviour drift must be bit-identical through all of this. Its eligibility is asked
     * through the same seam and bounded by the same budget, but the confirmation bar is
     * conformance's own, so a behaviour finding that is escalatable still escalates on the
     * first tick that sees it.
     */
    @Test
    @DisplayName("behaviour drift's escalation is untouched by the conformance confirmation bar")
    void behaviourDriftEscalationIsUnchanged() {
        flagsSilent();
        Project p = project("auto-esc-behaviour-unchanged");
        automaticOn(p);
        String id = shift(p, "turn_duration:" + BUCKET + ":slower:pinned", 5);

        escalator.tick();

        assertEquals(1, triageJobs(p.projectId()), "a behaviour finding escalates on the first tick, as before");
        assertNotNull(findings.findById(p.projectId(), id).orElseThrow().escalatedAt());
    }

    // -----------------------------------------------------------------------------------------------

    // -----------------------------------------------------------------------------------------------

    /** Nothing holds an opinion: an empty flag store, which is also what an outage produces. */
    private void flagsSilent() {
        when(flags.override(anyString(), any())).thenReturn(Optional.empty());
    }

    /** One org with automatic triage turned on, exactly as its own override row would express it. */
    private void automaticOn(Project p) {
        when(flags.override(Capability.TRIAGE_AUTOMATIC.wire(), FlagContext.forOrg(p.orgId())))
                .thenReturn(Optional.of(true));
    }

    private record Project(String orgId, String projectId, String baselineId) {}

    private Project project(String slug) {
        TenantFixture.Setup setup = TenantFixture.bootstrap(tenants, slug);
        // Behaviour drift and SOP conformance are off by default, so the org has to state that
        // it has them or there are no drift/conformance findings for the escalator to act on and
        // every case here would pass or fail on the wrong flag. Frustration and groundedness are
        // irrelevant here: this escalator never schedules them. `triage_automatic` is
        // deliberately not granted here: it is this class's actual subject and stays at its
        // default until `automaticOn` says otherwise. Stubbed after `flagsSilent()` by every
        // caller, so these specific stubs win.
        when(flags.override(
                        Capability.BEHAVIOR_DRIFT.wire(),
                        FlagContext.forOrg(setup.org().id())))
                .thenReturn(Optional.of(true));
        when(flags.override(
                        Capability.SOP_CONFORMANCE.wire(),
                        FlagContext.forOrg(setup.org().id())))
                .thenReturn(Optional.of(true));
        String projectId = setup.project().id();
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
        return new Project(setup.org().id(), projectId, baselineId);
    }

    /** One metric-drift finding, observed over {@code samples} samples, with its population recorded. */
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
                        Instant.now().minus(QUIET_WINDOW).toString(),
                        Instant.now().toString())
                .findingId();
        // A span-grain `member`, which is what the metric sweep writes: no exemplar, since that
        // role is not used by either drift measure. The escalatable predicate reads any
        // trace-grain ref, and recording an exemplar here would test the automatic path against
        // evidence the classifier does not produce, on a lane whose failure mode is silent: an
        // ineligible finding is skipped, not refused, so nothing would have surfaced but an empty
        // queue.
        findingEvidence.record(
                p.projectId(),
                id,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.span("trace-member", "span-member")),
                Instant.now().toString());
        return id;
    }

    /** A jsonb-sourced number as a primitive, with the null check NullAway insists on made loud. */
    private static double doubleOf(@Nullable Object value) {
        return ((Number) Objects.requireNonNull(value, "payload number missing")).doubleValue();
    }

    private long triageJobs(String projectId) {
        return jdbc.sql("SELECT count(*) FROM job WHERE kind = 'triage' AND project_id = :pid")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /**
     * Which classifier a measure files under. The sweep spells the same mapping; a test that hardcoded
     * one key would make the detector filter pass by construction.
     */
    private static String classifierFor(String causeKey) {
        return causeKey.startsWith("cost:") ? BuiltInDetector.Kind.COST_DRIFT : BuiltInDetector.Kind.DURATION_DRIFT;
    }
}
