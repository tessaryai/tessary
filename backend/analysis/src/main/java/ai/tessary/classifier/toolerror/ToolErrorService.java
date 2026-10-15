// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorRepository.RawFailure;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Recomputes a project's tool-error findings (tool-error.md §5, §6): replay the hourly aggregate and upsert one finding
 * per tool in a spell. Nothing carries over between passes, so it is idempotent. The write assigns counts, never
 * refreshes judgements, and writes the spell's own onset once.
 */
@Service
public class ToolErrorService {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorService.class);

    /**
     * Anchored to the project's newest tool-call event ({@link ToolErrorRepository#newestEventAt}), not wall-clock now,
     * so a months-old backfill still falls inside the window.
     */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /**
     * The quiet gap, in event time, after which a firing starts a new spell. Short because every pass re-evaluates
     * every tool. Measured from the spell's last folded hour, never wall clock.
     */
    public static final Duration QUIET_WINDOW = Duration.ofHours(6);

    static final int SIGNATURE_SAMPLE = 2000;

    /** Enough to open several instances; bounded because the blob is rendered whole. */
    static final int WITNESS_TRACES = 5;

    private final ToolErrorRepository repo;
    private final ToolErrorReferenceRepository references;
    private final ToolErrorStateRepository states;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidenceRefs;
    private final ClassifierRepository classifiers;
    private final ObjectMapper mapper;

    public ToolErrorService(
            ToolErrorRepository repo,
            ToolErrorReferenceRepository references,
            ToolErrorStateRepository states,
            FindingRepository findings,
            FindingEvidenceRepository evidenceRefs,
            ClassifierRepository classifiers,
            ObjectMapper mapper) {
        this.repo = repo;
        this.references = references;
        this.states = states;
        this.findings = findings;
        this.evidenceRefs = evidenceRefs;
        this.classifiers = classifiers;
        this.mapper = mapper;
    }

    Optional<ClassifierRow> definition(String projectId) {
        return classifiers.listByProject(projectId).stream()
                .filter(c -> BuiltInDetector.Kind.TOOL_ERROR.equals(c.detector()))
                .filter(ClassifierRow::enabled)
                .findFirst();
    }

    /** Must not write while the classifier is off, or its cases would stay alive. */
    public int refresh(String projectId) {
        Optional<ClassifierRow> signal = definition(projectId);
        if (signal.isEmpty()) return 0;
        ToolErrorConfig config = ToolErrorConfig.of(mapper, signal.get().configJson());

        // Empty means no tool-call traffic ever reached this project.
        Optional<Instant> anchor = repo.newestEventAt(projectId);
        if (anchor.isEmpty()) return 0;
        Instant from = anchor.get().minus(REPLAY_WINDOW);
        List<HourlyToolTally> tallies = repo.hourlyTallies(projectId, from);
        if (tallies.isEmpty()) return 0;
        // The bucket key is a lossy normalization and cannot be turned back into raw names.
        Map<String, List<String>> namesByToolKey = repo.namesByToolKey(projectId, from);

        Map<String, CarriedState> carriedByTool = states.byTool(projectId);
        // Read BEFORE the sweep advances anything: that is the state the ruling was made against.
        Set<String> awaitingPin = carriedByTool.values().stream()
                .filter(CarriedState::hasPendingPin)
                .map(CarriedState::toolKey)
                .collect(Collectors.toCollection(HashSet::new));

        ToolErrorTrend.Sweep sweep = ToolErrorTrend.sweep(tallies, config, references.byTool(projectId), carriedByTool);
        // Wall clock for bookkeeping only; last_seen_at and the quiet test use the spell's event clock.
        String now = Instant.now().toString();

        // State first: the other order could advance the watermark past evidence whose finding was never recorded.
        Set<String> absorbed = new HashSet<>();
        for (CarriedState advanced : sweep.advanced()) {
            states.save(projectId, advanced, now);
            if (installPendingPin(projectId, advanced, config, now)) absorbed.add(advanced.toolKey());
        }
        // A tool whose absorb was ruled on is not written, pinned or still pending: the absorb closed the finding,
        // which left `ux_finding_live`, so writing would INSERT a fresh open finding for a cause the human settled.
        List<Spell> spells = sweep.spells().stream()
                .filter(s -> !absorbed.contains(s.toolKey()) && !awaitingPin.contains(s.toolKey()))
                .toList();
        for (Spell spell : spells) {
            persist(projectId, spell, config, from, now, namesByToolKey.getOrDefault(spell.toolKey(), List.of()));
        }

        StructuredLog.info(log, Markers.OPS, "toolerror.refresh")
                .field("project", projectId)
                .field("buckets", tallies.size())
                .field("spells", spells.size())
                // Climbing means a producer stopped stamping start times, which otherwise shows only as slower
                // detection.
                .field("skippedNoStartedAt", repo.skippedNoStartedAt(projectId, from))
                .log();
        return spells.size();
    }

    /**
     * Fold a negatively-ruled window into the tool's reference and clear its arm, or the next sweep reopens a cause a
     * human just dismissed. Counts are ADDED to the reference (absorb replaces it): a negative only says this stretch
     * was ordinary. The accepted reference also stops the replay re-reading the window. Never on a positive; never
     * throws, since the ruling is already recorded.
     */
    public void foldRuledNegative(
            String projectId, String toolKey, String findingId, @Nullable String payloadJson, String now) {
        ToolErrorEvidence.Read read = ToolErrorEvidence.read(payloadJson);
        if (read == null) {
            StructuredLog.warn(log, Markers.OPS, "toolerror.fold.unreadable-payload")
                    .field("project", projectId)
                    .field("tool", toolKey)
                    .field("finding", findingId)
                    .log();
            return;
        }
        CarriedState carried = states.byTool(projectId).get(toolKey);
        if (carried == null) {
            StructuredLog.warn(log, Markers.OPS, "toolerror.fold.no-state")
                    .field("project", projectId)
                    .field("tool", toolKey)
                    .field("finding", findingId)
                    .log();
            return;
        }
        ToolErrorRate baseline = carried.baseline();

        // A pre-onset-rework blob counts the tool's whole history, which would count the baseline twice; the arm clears
        // but the reference is left alone.
        if (!read.countsAreOnsetRun() || baseline == null) {
            states.reset(projectId, toolKey, null, foldNote(findingId, "arm cleared; counts not fold-safe"), now);
            StructuredLog.info(log, Markers.OPS, "toolerror.fold.arm-only")
                    .field("project", projectId)
                    .field("tool", toolKey)
                    .field("finding", findingId)
                    .field("reason", baseline == null ? "no baseline" : "pre-onset counts")
                    .log();
            return;
        }

        long calls = baseline.calls() + read.nCur();
        long failures = baseline.failures() + read.failuresCur();
        references.pin(projectId, toolKey, calls, failures, "triage:" + findingId, now);
        states.reset(projectId, toolKey, null, foldNote(findingId, "window folded into the reference"), now);
        StructuredLog.info(log, Markers.OPS, "toolerror.fold.closed")
                .field("project", projectId)
                .field("tool", toolKey)
                .field("finding", findingId)
                .field("calls", calls)
                .field("failures", failures)
                .log();
    }

    private static String foldNote(String findingId, String what) {
        return "Triage closed finding " + findingId + " — " + what;
    }

    /**
     * Honour an absorb pressed before the run held {@code minBaselineCalls}: pinning a dozen calls would make a burst
     * the normal. Pins from the up arm's run, the one the finding was written about.
     */
    private boolean installPendingPin(String projectId, CarriedState carried, ToolErrorConfig config, String now) {
        if (!carried.hasPendingPin()) return false;
        long calls = carried.state().callsSinceOnsetUp();
        if (calls < config.minBaselineCalls()) return false;

        ToolErrorRate baseline = carried.baseline();
        if (baseline == null) return false;
        double p0 = ToolErrorDetector.baselineRate(baseline);
        long failures = ToolErrorDetector.failuresFromS(
                carried.state().sUp(), calls, p0, ToolErrorDetector.shiftedUp(p0, config));

        references.pin(projectId, carried.toolKey(), calls, failures, carried.pendingPinBy(), now);
        states.clearPendingPin(projectId, carried.toolKey(), now);
        states.reset(
                projectId,
                carried.toolKey(),
                carried.pendingPinBy(),
                "Absorbed once the run reached " + calls + " calls",
                now);
        StructuredLog.info(log, Markers.OPS, "toolerror.pin.deferred")
                .field("project", projectId)
                .field("tool", carried.toolKey())
                .field("calls", calls)
                .field("failures", failures)
                .log();
        return true;
    }

    private void persist(
            String projectId, Spell spell, ToolErrorConfig config, Instant from, String now, List<String> toolNames) {
        // The hour the detector last folded, so a backfill replays on the traffic's timeline. A firing spell always has
        // one.
        String eventAt = Objects.requireNonNull(spell.lastBucket());
        Instant until = Instant.parse(eventAt).plus(Duration.ofHours(1));
        String quietBefore = Instant.parse(eventAt).minus(QUIET_WINDOW).toString();

        Failures failing = patternsFor(projectId, spell, config, until, toolNames);
        String evidence = ToolErrorEvidence.toJson(
                spell.toolKey(),
                spell.decision(),
                failing.patterns(),
                failing.patterns().size() >= config.maxPatterns(),
                failing.traceIds(),
                from.toString(),
                until.toString());
        var recorded = findings.recordRecomputedCause(
                Ids.ulid(),
                projectId,
                ToolErrorEvidence.causeKey(spell.toolKey(), spell.decision()),
                spell.decision().callsSinceOnset(),
                ai.tessary.classifier.substrate.BehaviorSubstrateRepository.UNATTRIBUTED,
                spell.onsetBucket(),
                evidence,
                eventAt,
                quietBefore,
                now);
        if (recorded == null) return;
        recordPopulation(projectId, recorded.findingId(), spell, toolNames, until, now);
    }

    /**
     * Every call since onset as {@code member} and the failing subset as {@code witness}: the rate's denominator and
     * numerator, both in full. No baseline (a CUSUM reference is a fitted rate, not rows) and no exemplar. Re-recorded
     * each pass as the spell grows; {@code until} bounds it to the last folded hour so it matches
     * {@code toolError.nCur}.
     */
    private void recordPopulation(
            String projectId, String findingId, Spell spell, List<String> toolNames, Instant until, String now) {
        Instant onset = Instant.parse(Objects.requireNonNull(spell.onsetBucket()));
        record(
                projectId,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                repo.callRefsFor(projectId, toolNames, onset, until),
                now);
        record(
                projectId,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                repo.failingCallRefsFor(projectId, toolNames, onset, until),
                now);
    }

    private void record(
            String projectId, String findingId, String role, List<ToolErrorRepository.SpanRef> calls, String now) {
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>(calls.size());
        for (var call : calls) {
            refs.add(FindingEvidenceRepository.Ref.span(call.traceId(), call.spanId()));
        }
        evidenceRefs.record(projectId, findingId, role, refs, now);
    }

    /** {@code traceIds} is a preview for the dossier; the finding's evidence enumerates failures in full. */
    private record Failures(List<ToolErrorRate.Pattern> patterns, List<String> traceIds) {}

    /**
     * Read only for a tool that already alarmed, over the observed side. {@code toolNames} are the raw names, not
     * recoverable from {@code spell.toolKey()}. Bounded by {@code until} like {@link #recordPopulation}.
     */
    private Failures patternsFor(
            String projectId, Spell spell, ToolErrorConfig config, Instant until, List<String> toolNames) {
        Instant since = Instant.parse(Objects.requireNonNull(spell.onsetBucket()));
        ToolErrorRate observed = new ToolErrorRate();
        List<String> traces = new ArrayList<>();
        for (RawFailure f : repo.failuresFor(projectId, toolNames, since, until, SIGNATURE_SAMPLE)) {
            ToolFailure.Recognized r = ToolFailure.recognize(
                    f.errorType(), f.isError(), f.errorTypeAttr(), f.exceptionAttr(), parseJson(f.resultJson()));
            // A row no rule recognizes changed under the read; dropped because the breakdown cannot explain it.
            if (r == null) continue;
            observed.add(r);
            String traceId = f.traceId();
            if (traceId != null && !traceId.isBlank() && traces.size() < WITNESS_TRACES && !traces.contains(traceId)) {
                traces.add(traceId);
            }
        }
        return new Failures(ToolErrorRate.ranked(observed, config.maxPatterns()), List.copyOf(traces));
    }

    private @Nullable JsonNode parseJson(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
