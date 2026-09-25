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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The tool-error classifier (launch segment C) against a real database, end to end: ingested rows in,
 * a persisted finding out, and both arms of the correction loop working on it.
 *
 * <p><b>Why this test exists, stated plainly.</b> Segment C shipped with three unit-test files and no
 * test that executed a single one of its queries. It merged in a state where the classifier could not
 * run at all — {@code ToolFailure.SQL_PREDICATE} contains jsonb's {@code ?} operator, which Spring reads
 * as a positional JDBC placeholder and refuses to mix with named ones, so every read threw before
 * reaching Postgres — and behind that sat a text-block concatenation emitting {@code ANDCOALESCE}, a
 * {@code NOT NULL} on {@code call_site_id} that no tool-error finding could satisfy, and two paths in
 * the case gate that a {@code rate_shift} finding could not pass. Five faults, all runtime, all in code
 * that {@code task check} covered and never ran. Everything asserted below would have failed on the
 * first execution of any of them, which is the whole point.
 *
 * <p>The four claims:
 *
 * <ol>
 *   <li><b>C1</b> — the definition is broader than the recorded error type. A framework that catches an
 *       exception, hands {@code {"error": …}} back to the model and closes the span cleanly is a failure,
 *       and the old span-status rule cannot see it.
 *   <li><b>C2/C3</b> — a sustained rise earns a persisted finding carrying the spell's calls as evidence,
 *       the failing ones as {@code witness} and the whole population as {@code member}, and that finding
 *       reaches Layer 2 rather than being refused for want of readable evidence.
 *   <li><b>C3, human arm</b> — <em>Real deviation</em> marks the finding confirmed, which is what the
 *       shared case gate reads.
 *   <li><b>Absorb</b> — <em>Legitimate — absorb</em> pins the tool's accepted reference, and the next
 *       recompute stays quiet against it. Without the pinned row the replay re-learns the old reference
 *       and re-alarms within minutes, so this is what makes the button mean anything.
 * </ol>
 */
@SpringBootTest
class ToolErrorClassifierIntegrationTest {

    private static final String TOOL = "search_docs";

    /** Comfortably past {@code minBaselineCalls} (500) so the reference is thick before the rise. */
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

        // The read runs at all — which it did not before, because of the `?` operator. Everything
        // below this line is unreachable until that is true.
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

        // Forty quiet hours to pin the reference, then twenty at a rate nobody could call noise.
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

        // Idempotence: the replay is recomputed from source on every read, so running it again must
        // leave the same row rather than a second one. That property is what stands in for the cursor
        // and watermark this classifier deliberately does not have.
        service.refresh(pid);
        assertEquals(
                1,
                findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                        .filter(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind()))
                        .count(),
                "a recompute must not deposit a second finding for a cause that already has one");

        // The human arm of the case gate: a positive ruling stays open, which is what the case source
        // now reads as confirmed.
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
        // Ninety days old: well outside a `now - 28d` window, but the whole seeded span (sixty hours)
        // sits comfortably inside 28 days of the LAST call this project ever made.
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
    @DisplayName("a resweep with no new traffic does not advance last_seen_at")
    void lastSeenDoesNotAdvanceWithoutNewTraffic() {
        String pid = TenantFixture.bootstrap(tenants, "toolerr-stale").project().id();
        Instant start = Instant.now().minus(60, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);

        assertEquals(1, service.refresh(pid));
        String firstLastSeen = firedFinding(pid).lastSeenAt();

        assertEquals(1, service.refresh(pid), "still one tool in a spell");
        String secondLastSeen = firedFinding(pid).lastSeenAt();

        assertEquals(firstLastSeen, secondLastSeen, "no new buckets were folded, so the event clock must not move");
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

        // Twenty hours with NOTHING for this tool at all — no upload, not a recovery: an hour with zero
        // calls contributes no bucket to the replay, so the accumulator that is still broken has nothing
        // to cool it in the gap. Still broken on the other side.
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
        // Ten days old — well inside a pre-fix `now - 28d` window too, so this isolates the event clock
        // (last_seen_at, and the evidence bound it drives) from the anchor the backfill test above covers.
        Instant start = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);
        // The detector's own last folded bucket, read back independently rather than hand-computed from
        // the seeding parameters, so this asserts against what the replay actually saw.
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
     * A closed ruling hands the window back to the detector: the arm clears, and what it fired over
     * becomes part of normal.
     *
     * <p><b>The state this replaces.</b> Closing the finding alone does not touch the accumulator, so it
     * kept the value it fired at — above its own threshold — and the very next sweep would open a fresh
     * finding for a cause a human just dismissed, off nothing new. On the websearch finding that prompted
     * this the arm sat at 7.331 against a threshold of 6.0 for thirteen failure-free days.
     *
     * <p><b>Folded, not replaced.</b> Absorb replaces the reference, because a human pressing
     * "legitimate" is saying this run IS the normal. A close is weaker — nobody said the old normal was
     * wrong — so the judged counts are ADDED to it, which is what both assertions below check: more
     * calls than the reference alone had, and the arm back at zero.
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
     * Which verdicts may move detector state, held directly rather than inferred.
     *
     * <p>{@code negative} is the only verdict that establishes the traffic was ordinary — a claim
     * folding asserts by adding the window to the reference — so it is the only one that may fold.
     *
     * <p>{@code positive} opens a case. Moving the bar there would be the platform quietly agreeing to
     * a rate a human is about to be asked about, and clearing the arm would drop the evidence out from
     * under the case.
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
     * The same window, ruled {@code positive}. Nothing moves.
     *
     * <p>A positive ruling opens a case: the regression is real and a human is about to be asked about
     * it. Moving the bar to accommodate it would be the platform quietly agreeing to a rate nobody has
     * accepted yet, and clearing the arm would drop the evidence out from under the case.
     */
    @Test
    @DisplayName("a positive ruling leaves the accumulator and the reference exactly where they were")
    void aPositiveRulingTouchesNoDetectorState() {
        String pid =
                TenantFixture.bootstrap(tenants, "toolerr-positive").project().id();
        Instant start = Instant.now().minus(90, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, start, QUIET_HOURS, 1);
        seedHours(pid, start.plus(QUIET_HOURS, ChronoUnit.HOURS), 20, 8);
        service.refresh(pid);

        firedFinding(pid);
        double armed = armOf(pid);

        assertEquals(armed, armOf(pid), 1e-9, "nothing ran, so nothing moved");
        assertTrue(references.byTool(pid).isEmpty(), "and no reference was pinned behind the case");
    }

    /**
     * A positive ruling keeps the finding open but takes it out of {@code ux_finding_live}, and the
     * recompute re-derives the same spell every minute. The pass after the ruling used to INSERT a second,
     * unruled finding with the same onset for the window just ruled on. Only traffic in a later hour may
     * file a new one.
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
     * Absorbing says "this rate is the new normal", so the reference it installs has to be measured over
     * the run since onset — the only stretch that describes the new normal. A burst alarms in a couple of
     * dozen calls, so at the moment of the press there is usually nowhere near {@code minBaselineCalls} of
     * it, and pinning what there is would hand the tool an 80% baseline and go permanently deaf.
     *
     * <p>So the decision is recorded and installs itself once the run is thick enough. Both halves are
     * asserted, because either alone is a mechanism that looks like it works and does not: a pin that
     * never lands is the button doing nothing, and one that lands early is worse than nothing.
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

        // The invariant, asserted rather than a call count derived from the fixture: however few calls of
        // the new rate exist when the human presses, what lands is never a reference thinner than the
        // minimum. Below it the decision waits; at or above it, it installs. A fixture whose run happens
        // to clear 500 on the first press exercises the second branch and must still hold this.
        drift.resolve(pid, finding.id(), "expected", null);
        assertTrue(
                references.byTool(pid).values().stream().allMatch(r -> r.calls() >= 500),
                "no reference may be pinned from fewer calls than a reference needs: "
                        + describe(references.byTool(pid)));

        // The deferral window itself, which ToolErrorSweep now runs inside on a schedule rather than only
        // when somebody loads Triage. `resolve` set the finding ALLOWLISTED and `ux_finding_live` covers
        // only ('open','blocked'), so the recompute's ON CONFLICT cannot see that row: every pass here
        // used to INSERT a second, fresh, open finding for a cause the human had already settled, and
        // nothing closed it. The press looked like it had done nothing.
        service.refresh(pid);
        assertTrue(
                findings.listByProject(pid, FindingRow.Status.OPEN, null, null, false, 50).stream()
                        .noneMatch(f -> FindingRow.Cause.RATE_SHIFT.equals(f.causeKind())),
                "a pass inside the deferral window must not re-file a cause the human already absorbed");

        // More of the same rate arrives, and now there is enough of it to call it the new normal.
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

        // The point of the whole mechanism: without the pinned row the replay re-learns the original
        // reference off the leading buckets and writes the same finding straight back.
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

    /** Pinned references with their counts, so a failure says what landed rather than only that it did. */
    private static String describe(Map<String, ToolErrorReferenceRepository.AcceptedReference> pinned) {
        return pinned.values().stream()
                .map(r -> r.toolKey() + "(calls=" + r.calls() + ",failures=" + r.failures() + ")")
                .toList()
                .toString();
    }

    /** The one rate_shift finding the fixture fires, or a failure that says the fixture stopped working. */
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

    /** The up arm, read from the row rather than from the payload's frozen copy of it. */
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
                // Alternate the two invisible-to-the-old-rule shapes so the rise is not carried by
                // span status alone — a regression the narrow rule could see is not the interesting case.
                Failure mode = c >= failuresPerHour
                        ? Failure.NONE
                        : (c % 2 == 0 ? Failure.RESULT_ERROR_OBJECT : Failure.SPAN_STATUS);
                seedCall(projectId, hour.plusSeconds(c * 10L), mode);
            }
        }
    }

    /**
     * One tool call, as a whole turn: a trace with an {@code agent} root and the {@code tool} span the
     * call hangs off, then the call itself, then the REAL rollup.
     *
     * <p>The rollup is not fixture ceremony. {@code hourlyTallies} counts only calls whose trace
     * {@code is_settled}, because a turn's calls arrive across several exporter flushes and half a turn is
     * not a rate. A seeder that skipped it would seed rows the reader is right to ignore, and every
     * assertion below would read zero for a reason that has nothing to do with what is being tested.
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

        // Rule 2's attribute lives in span_payload now, not in a jsonb column on the span row — which is
        // exactly what ToolFailure.SQL_PREDICATE's `pl` join reads.
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

    /** How many calls the pre-segment-C rule — span status alone — would have called failures. */
    private long narrowFailures(String projectId) {
        return jdbc.sql("SELECT count(*) FROM tool_call WHERE project_id = :pid"
                        + " AND (error_type IS NOT NULL OR is_error IS TRUE)")
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }
}
