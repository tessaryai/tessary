// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The tool-error classifier against a real database: ingested rows in, a persisted finding out, and both arms of the
 * correction loop.
 *
 * <p>It exists because segment C merged with no test that ran its queries, and five runtime faults hid there: jsonb's
 * {@code ?} read as a JDBC placeholder, an {@code ANDCOALESCE} concatenation, an unsatisfiable {@code NOT NULL} on
 * {@code call_site_id}, and two case-gate paths a {@code rate_shift} finding could not pass.
 *
 * <p>Claims: a caught {@code {"error": …}} on a cleanly closed span is a failure (C1); a sustained rise files a
 * finding with failing calls as witness and the population as member, which reaches Layer 2 (C2/C3); a positive
 * ruling confirms it; and absorb pins the new reference so the next recompute stays quiet.
 */
@SpringBootTest
class ToolErrorClassifierIntegrationTest {

    private static final String TOOL = "search_docs";

    /** Past {@code minBaselineCalls} (500), so the reference is thick before the rise. */
    private static final int QUIET_HOURS = 40;

    private static final int CALLS_PER_HOUR = 20;

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ToolErrorRepository repo;

    @Autowired
    ToolErrorService service;

    @Autowired
    ToolErrorReferenceRepository references;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingService drift;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    @DisplayName("a result that declares its own error is a failure; the old span-status rule misses it")
    void theDefinitionReachesPastSpanStatus() {
        String pid = TenantFixture.bootstrap(tenants, "toolerr-def").project().id();
        Instant hour = Instant.now().minus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

        seedCall(pid, hour, Failure.NONE);
        seedCall(pid, hour, Failure.SPAN_STATUS);
        seedCall(pid, hour, Failure.RESULT_ERROR_OBJECT);
        seedCall(pid, hour, Failure.RESULT_IS_ERROR);
        seedCall(pid, hour, Failure.ERROR_TYPE_ATTRIBUTE);

        // The read runs at all, which the {@code ?} operator once prevented.
        List<HourlyToolTally> tallies = repo.hourlyTallies(pid, hour.minus(1, ChronoUnit.HOURS));
        HourlyToolTally tally = tallies.stream()
                .filter(t -> t.toolKey().endsWith(TOOL))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bucket for " + TOOL + "; got " + tallies));

        assertEquals(5, tally.calls(), "every call is the denominator, failing or not");
        assertEquals(
                4,
                tally.failures(),
                "all four recognition rules count — a clean span carrying {\"error\": …} is the one "
                        + "the narrow rule cannot see, and it is the common shape in an agent product");

        assertEquals(
                1,
                narrowFailures(pid),
                "the pre-segment-C rule sees only the span-status failure, which is what C1 exists to fix");
    }

    @Test
    @DisplayName("a sustained rise persists a finding that Layer 2 will accept, and both verbs resolve it")
    void aSustainedRiseBecomesAFindingAndResolves() {
        String pid = TenantFixture.bootstrap(tenants, "toolerr-rise").project().id();
        Instant start = Instant.now().minus(60, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);

        assertEquals(1, service.refresh(pid), "one tool in a spell");

        List<FindingRow> open = findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50);
        FindingRow finding = open.stream()
                .filter(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rate_shift finding was written"));

        assertTrue(finding.causeKey().contains(TOOL), "the cause key names the tool that moved");
        assertTrue(finding.causeKey().endsWith(":up"), "the rise arm, not the improvement arm");
        assertNotNull(
                finding.callSiteId(),
                "call_site_id is NOT NULL in the schema; a null here is an insert that never happened");
        Map<String, Long> byRole = evidence.countsByRole(pid, finding.id());
        assertTrue(
                byRole.getOrDefault(FindingEvidenceRow.Role.WITNESS, 0L) > 0,
                "the failing calls have to reach Layer 2 as evidence rows, enumerated rather than sampled");
        assertTrue(
                byRole.getOrDefault(FindingEvidenceRow.Role.MEMBER, 0L)
                        > byRole.getOrDefault(FindingEvidenceRow.Role.WITNESS, 0L),
                "member is the denominator and witness the numerator, so the population must be the larger");
        assertEquals(
                0,
                byRole.getOrDefault(FindingEvidenceRow.Role.BASELINE, 0L),
                "a CUSUM compares against a fitted rate, not a window of rows, so there is no baseline to write");
        assertEquals(
                0,
                byRole.getOrDefault(FindingEvidenceRow.Role.EXEMPLAR, 0L),
                "naming one trace as the way in biases the run that reads it");
        assertTrue(
                finding.payloadJson() != null && finding.payloadJson().contains("failing_traces"),
                "the evidence names traces the failures happened in, for a human and for the dossier");

        // The replay recomputes from source, so a second run must leave the same row; that stands in for the cursor
        // this classifier does not have.
        service.refresh(pid);
        assertEquals(
                1,
                findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                        .filter(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind()))
                        .count(),
                "a recompute must not deposit a second finding for a cause that already has one");

        // A positive ruling stays open, which the case source reads as confirmed.
        var resolved = drift.resolve(pid, finding.id(), "not_expected", null);
        assertEquals(
                FindingRow.Status.OPEN,
                resolved.status(),
                "Real deviation must mark the finding confirmed — this 404'd before rate_shift had a "
                        + "branch in resolve, so no tool-error case could open by any path");
        assertEquals(FindingRow.TriageVerdict.POSITIVE, resolved.triageVerdict());
    }

    @Test
    @DisplayName("a wholly historical backfill still fires — the replay anchors to the project's own "
            + "traffic, not to wall-clock now")
    void aBackfillOlderThanTheReplayWindowStillFires() {
        String pid =
                TenantFixture.bootstrap(tenants, "toolerr-backfill").project().id();
        // Ninety days old: outside a {@code now - 28d} window, but inside 28 days of this project's last call.
        Instant start = Instant.now().minus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);

        assertEquals(
                1,
                service.refresh(pid),
                "wall-clock now-28d would read nothing but empty months; the project's own newest "
                        + "tool-call event is the anchor instead");
        assertTrue(
                findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                        .anyMatch(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind())),
                "a rate_shift finding was written from data entirely outside the wall-clock window");
    }

    @Test
    @DisplayName("a gap between folded buckets longer than the quiet window does not reset the onset "
            + "while the underlying spell never actually recovered")
    void aGapLongerThanTheQuietWindowKeepsTheOnsetWhileTheSpellIsUnbroken() {
        String pid = TenantFixture.bootstrap(tenants, "toolerr-gap").project().id();
        Instant start = Instant.now().minus(90, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 10, 8); // breaks

        assertEquals(1, service.refresh(pid));
        FindingRow first = firedFinding(pid);
        String onset = first.onsetAt();
        assertNotNull(onset);

        // Twenty hours with no calls for this tool: an empty hour adds no bucket, so the broken accumulator never
        // cools across the gap.
        seedHours(pid, start.plus(QUIET_HOURS + 30L, ChronoUnit.HOURS), 10, 8);

        assertEquals(1, service.refresh(pid), "still one tool in a spell, not a second one");
        FindingRow second = firedFinding(pid);

        assertEquals(
                first.id(),
                second.id(),
                "the same finding — a gap the detector never recovered through is not a new spell");
        assertEquals(
                onset,
                second.onsetAt(),
                "the onset must not drift across a gap in the UPLOAD when the spell itself was never broken");
        assertTrue(
                Instant.parse(second.lastSeenAt()).isAfter(Instant.parse(first.lastSeenAt())),
                "last_seen_at still advances to the newly folded traffic");
    }

    @Test
    @DisplayName("last_seen_at, and the evidence bounded by it, stop at the last hour the detector "
            + "folded, not at the moment the sweep ran")
    void memberAndWitnessStopAtTheLastFoldedHour() {
        String pid =
                TenantFixture.bootstrap(tenants, "toolerr-bounded").project().id();
        // Ten days old, inside a {@code now - 28d} window too, so this isolates the event clock from the anchor the
        // backfill test covers.
        Instant start = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);
        // The replay's own last folded bucket, read back rather than computed from the seed.
        String lastFoldedBucket = repo.hourlyTallies(pid, start).stream()
                .filter(t -> t.toolKey().endsWith(TOOL))
                .map(HourlyToolTally::bucket)
                .max(String::compareTo)
                .orElseThrow(() -> new AssertionError("no buckets tallied for " + TOOL));

        assertEquals(1, service.refresh(pid));
        FindingRow finding = firedFinding(pid);

        assertEquals(
                lastFoldedBucket,
                finding.lastSeenAt(),
                "last_seen_at is the last hour actually folded, not Instant.now() at write time");
        assertTrue(
                Instant.parse(finding.lastSeenAt()).isBefore(Instant.now().minus(1, ChronoUnit.DAYS)),
                "the event clock, not the moment the sweep wrote the row");
        assertNotNull(finding.onsetAt());

        Instant onset = Instant.parse(finding.onsetAt());
        Instant until = Instant.parse(finding.lastSeenAt()).plus(1, ChronoUnit.HOURS);
        long expectedMembers =
                repo.callRefsFor(pid, List.of(TOOL), onset, until).size();
        long expectedWitnesses =
                repo.failingCallRefsFor(pid, List.of(TOOL), onset, until).size();

        Map<String, Long> byRole = evidence.countsByRole(pid, finding.id());
        assertEquals(
                expectedMembers,
                byRole.getOrDefault(FindingEvidenceRow.Role.MEMBER, 0L),
                "member is bounded to [onset, last folded hour], not to wall-clock now");
        assertEquals(
                expectedWitnesses,
                byRole.getOrDefault(FindingEvidenceRow.Role.WITNESS, 0L),
                "witness follows the same bound as member");
    }

    /**
     * A closed ruling hands the window back to the detector: the arm clears and the window folds into normal. Before,
     * the arm kept its above-threshold value and the next sweep re-filed a dismissed cause; one arm sat at 7.331
     * against 6.0 for thirteen failure-free days.
     *
     * <p>Folded, not replaced: absorb replaces the reference, while a close only adds the judged counts to it.
     */
    @Test
    @DisplayName("a closed ruling folds the judged window into the reference and clears the arm")
    void aClosedRulingFoldsTheWindowAndResetsTheArm() {
        String pid = TenantFixture.bootstrap(tenants, "toolerr-fold").project().id();
        Instant start = Instant.now().minus(90, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);
        service.refresh(pid);

        FindingRow finding = firedFinding(pid);
        double armed = armOf(pid);
        long baselineCalls = baselineCallsOf(pid);
        assertTrue(armed > 0, "the fixture has to leave a fired arm, or this asserts nothing");

        service.foldRuledNegative(
                pid,
                finding.subjectId(),
                finding.id(),
                finding.payloadJson(),
                Instant.now().toString());

        assertEquals(0.0, armOf(pid), 1e-9, "the arm the finding fired on has to start again from zero");
        assertNull(onsetOf(pid), "and its onset with it — a cleared arm holding an onset is a half-reset");

        var pinned = references.byTool(pid).get(finding.subjectId());
        assertNotNull(pinned, "the judged window has to land as the tool's reference, or nothing was learned");
        assertTrue(
                pinned.calls() > baselineCalls,
                "the window is ADDED to the reference, so the count can only grow: was " + baselineCalls + ", now "
                        + pinned.calls());
        assertTrue(
                pinned.failures() > 0,
                "the failures ride with the calls — folding only the denominator would make the tool look"
                        + " better than the traffic it was just judged over");
    }

    /**
     * A fold that cannot trust the ruling's counts moves only what it can: an unreadable payload or stateless tool
     * moves nothing, and a pre-onset-rework payload would double count history, so only the arm clears.
     */
    @Test
    @DisplayName("a fold that cannot trust the ruling's counts clears the arm at most, never the reference")
    void aFoldThatCannotTrustTheCountsLeavesTheReferenceAlone() throws Exception {
        String pid = TenantFixture.bootstrap(tenants, "toolerr-fold-refused")
                .project()
                .id();
        Instant start = Instant.now().minus(90, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);
        service.refresh(pid);
        FindingRow finding = firedFinding(pid);
        double armed = armOf(pid);
        assertTrue(armed > 0, "the fixture has to leave a fired arm, or this asserts nothing");
        String now = Instant.now().toString();

        service.foldRuledNegative(pid, finding.subjectId(), finding.id(), "{not json", now);
        service.foldRuledNegative(pid, "tool:never_called", finding.id(), finding.payloadJson(), now);
        assertEquals(armed, armOf(pid), 1e-9, "neither refusal touched the arm");

        ObjectNode preOnset = (ObjectNode) new ObjectMapper().readTree(Objects.requireNonNull(finding.payloadJson()));
        assertNotNull(preOnset.remove("counts_basis"), "the fixture blob has to carry counts_basis");
        service.foldRuledNegative(pid, finding.subjectId(), finding.id(), preOnset.toString(), now);

        assertEquals(0.0, armOf(pid), 1e-9, "the ruling stands, so the arm still clears");
        assertNull(
                references.byTool(pid).get(finding.subjectId()),
                "whole-history counts must not be added to the reference");
    }

    /**
     * Only {@code negative} establishes the traffic was ordinary, so only it may fold. {@code positive} opens a case,
     * and moving the bar or clearing the arm there would undercut the case.
     */
    @Test
    @DisplayName("only a negative ruling may move detector state")
    void onlyANegativeRulingMovesDetectorState() {
        assertTrue(
                FindingRow.TriageVerdict.movesDetectorState(FindingRow.TriageVerdict.NEGATIVE),
                "a negative is the one ruling that establishes the window was ordinary");
        assertFalse(
                FindingRow.TriageVerdict.movesDetectorState(FindingRow.TriageVerdict.POSITIVE),
                "a positive opens a case; the bar must not move under it");
    }

    /**
     * A positive ruling leaves {@code ux_finding_live}, and the recompute re-derives the same spell every minute; the
     * next pass used to insert a duplicate unruled finding. Only a later hour of traffic may file a new one.
     */
    @Test
    @DisplayName("a recompute after a positive ruling files nothing until a later hour of traffic arrives")
    void aRuledSpellIsNotReFiledUntilNewerTraffic() {
        String pid = TenantFixture.bootstrap(tenants, "toolerr-ruled-resweep")
                .project()
                .id();
        Instant start = Instant.now().minus(90, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);
        service.refresh(pid);
        FindingRow ruled = firedFinding(pid);
        assertEquals(
                1,
                findings.recordTriage(
                        pid,
                        ruled.id(),
                        FindingRow.TriageVerdict.POSITIVE,
                        "The failure rate rose.",
                        null,
                        Instant.now().toString()));

        assertEquals(1, service.refresh(pid), "the spell is still running");
        assertEquals(1, rateShiftFindings(pid), "an unchanged recompute must not re-file the ruled window");

        seedHours(pid, start.plus(QUIET_HOURS + 20L, ChronoUnit.HOURS), 2, 8);
        service.refresh(pid);
        assertEquals(2, rateShiftFindings(pid), "a later hour of traffic is a window nobody ruled on");
        FindingRow fresh = findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                .filter(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind()) && !f.id().equals(ruled.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fresh rate_shift finding was written"));
        assertNull(fresh.triageVerdict(), "the fresh finding is unruled");
        assertTrue(
                Instant.parse(fresh.lastSeenAt()).isAfter(Instant.parse(ruled.lastSeenAt())),
                "and covers only the newer traffic's clock");
    }

    /**
     * Absorb pins a reference measured over the run since onset. At the press there is usually far less than {@code
     * minBaselineCalls} of it, and pinning that would leave an 80% baseline and a deaf detector, so the decision
     * waits until the run is thick enough. Both halves are asserted: a pin that never lands and one that lands early
     * both look like they work.
     */
    @Test
    @DisplayName("absorbing waits for enough of the new rate, then pins it and goes quiet")
    void absorbingDefersUntilTheRunIsThickEnoughThenPins() {
        String pid =
                TenantFixture.bootstrap(tenants, "toolerr-absorb").project().id();
        Instant start = Instant.now().minus(90, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);

        service.refresh(pid);
        FindingRow finding = findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                .filter(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rate_shift finding was written"));

        // However few new-rate calls exist at the press, the pinned reference is never thinner than the minimum.
        drift.resolve(pid, finding.id(), "expected", null);
        assertTrue(
                references.byTool(pid).values().stream().allMatch(r -> r.calls() >= 500),
                "no reference may be pinned from fewer calls than a reference needs: "
                        + describe(references.byTool(pid)));

        // The deferral window, which ToolErrorSweep runs on a schedule. The allowlisted finding is outside {@code
        // ux_finding_live}, so every pass used to insert a fresh open finding for a settled cause.
        service.refresh(pid);
        assertTrue(
                findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                        .noneMatch(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind())),
                "a pass inside the deferral window must not re-file a cause the human already absorbed");

        seedHours(pid, start.plus(QUIET_HOURS + 20L, ChronoUnit.HOURS), 20, 8);
        service.refresh(pid);

        Map<String, ToolErrorReferenceRepository.AcceptedReference> pinned = references.byTool(pid);
        var accepted = pinned.values().stream()
                .filter(r -> r.toolKey().endsWith(TOOL))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the deferred absorb never landed; got " + pinned.keySet()));
        assertTrue(accepted.calls() >= 500, "the reference must span enough calls to be one, got " + accepted.calls());
        assertTrue(
                accepted.failures() > 0,
                "absorbing an elevated rate must pin the elevated counts, not an empty window");

        // Without the pinned row the replay re-learns the old reference and re-files the same finding.
        assertEquals(0, service.refresh(pid), "the absorbed tool must not re-alarm against its own new reference");

        assertTrue(
                findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                        .noneMatch(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind())),
                "and it must not reappear as a fresh open finding either");
    }

    // ---- seeding ------------------------------------------------------------------------------

    private enum Failure {
        NONE,
        /** Rule 1: OTel span status ERROR, which ingest writes to {@code tool_call.error_type}. */
        SPAN_STATUS,
        /** Rule 2: an {@code error.type} attribute in the span's payload, status unset. */
        ERROR_TYPE_ATTRIBUTE,
        /** Rule 4: a top-level {@code error} object in the result, span closed cleanly. */
        RESULT_ERROR_OBJECT,
        /** Rule 4, MCP flavour: {@code isError} beside a content block. */
        RESULT_IS_ERROR
    }

    private static String describe(Map<String, ToolErrorReferenceRepository.AcceptedReference> pinned) {
        return pinned.values().stream()
                .map(r -> r.toolKey() + "(calls=" + r.calls() + ",failures=" + r.failures() + ")")
                .toList()
                .toString();
    }

    private FindingRow firedFinding(String projectId) {
        return findings.listByProject(projectId, FindingRow.Status.OPEN, null, null, false, 50).stream()
                .filter(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rate_shift finding was written"));
    }

    private long rateShiftFindings(String projectId) {
        return jdbc.sql("SELECT count(*) FROM finding WHERE project_id = :pid AND classifier_key = 'tool_error'")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /** The up arm, read from the row, not the payload's frozen copy. */
    private double armOf(String projectId) {
        return jdbc.sql("SELECT max(s_up) FROM tool_error_state WHERE project_id = :pid")
                .param("pid", projectId)
                .query(Double.class)
                .single();
    }

    private @Nullable String onsetOf(String projectId) {
        return jdbc.sql("SELECT max(onset_up_at) FROM tool_error_state WHERE project_id = :pid")
                .param("pid", projectId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private long baselineCallsOf(String projectId) {
        return jdbc.sql("SELECT coalesce(max(baseline_calls), 0) FROM tool_error_state WHERE project_id = :pid")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    private void seedHours(String projectId, Instant from, int hours, int failuresPerHour) {
        for (int h = 0; h < hours; h++) {
            Instant hour = from.plus(h, ChronoUnit.HOURS);
            for (int c = 0; c < CALLS_PER_HOUR; c++) {
                // Alternate the two shapes the old rule cannot see, so the rise is not carried by span status alone.
                Failure mode = c >= failuresPerHour
                        ? Failure.NONE
                        : (c % 2 == 0 ? Failure.RESULT_ERROR_OBJECT : Failure.SPAN_STATUS);
                seedCall(projectId, hour.plusSeconds(c * 10L), mode);
            }
        }
    }

    /**
     * One tool call as a whole turn, then the real rollup: {@code hourlyTallies} counts only settled traces, so
     * skipping it would make every assertion read zero.
     */
    private void seedCall(String projectId, Instant at, Failure failure) {
        String traceId = SubstrateV2Fixtures.traceId();
        String rootId = SubstrateV2Fixtures.spanId();
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.spanSeed(projectId)
                .traceId(traceId)
                .spanId(rootId)
                .sessionId(sessionId)
                .kind("agent")
                .name("loop")
                .at(at)
                .write();

        // Rule 2's attribute lives in span_payload, which the predicate's {@code pl} join reads.
        SpanRef span = fx.spanSeed(projectId)
                .traceId(traceId)
                .parentSpanId(rootId)
                .sessionId(sessionId)
                .kind("tool")
                .name("execute_tool " + TOOL)
                .at(at)
                .payload(
                        null,
                        null,
                        failure == Failure.ERROR_TYPE_ATTRIBUTE ? "{\"error.type\": \"SearchTimeout\"}" : null)
                .writeRef();

        String errorType = failure == Failure.SPAN_STATUS ? "upstream search timed out after 30014ms" : null;
        String result =
                switch (failure) {
                    case RESULT_ERROR_OBJECT -> "{\"error\": {\"message\": \"index shard 7 unavailable\"}}";
                    case RESULT_IS_ERROR -> "{\"isError\": true, \"content\": \"backend refused the query\"}";
                    default -> "{\"docs\": [\"policy.md\"]}";
                };
        fx.toolCall(projectId, span, TOOL, errorType, result, at);

        fx.rollup(projectId, traceId);
    }

    /** Failures under the old span-status-only rule. */
    private long narrowFailures(String projectId) {
        return jdbc.sql("SELECT count(*) FROM tool_call WHERE project_id = :pid"
                        + " AND (error_type IS NOT NULL OR is_error IS TRUE)")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }
}
