// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRepository.ConfirmedSpan;
import ai.tessary.classifier.finding.FindingRepository.Recorded;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricControl.Resolved;
import ai.tessary.classifier.metric.MetricDriftConfig.Measured;
import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import ai.tessary.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.classifier.metric.MetricDriftDetector.Silence;
import ai.tessary.classifier.metric.MetricFindingEvidence.Explained;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import ai.tessary.classifier.metric.MetricSource.Measurement;
import ai.tessary.classifier.metric.MetricSource.TokenReadings;
import ai.tessary.classifier.metric.MetricSource.TurnMetrics;
import ai.tessary.classifier.metric.MetricSuppression.Shift;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository.TraceHead;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.classifier.worker.ClassifierSweep;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.classifier.worker.SweepContext;
import ai.tessary.classifier.worker.SweepOutcome;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The WINDOW-grain sweep of the classifier worker, alongside the trace-grain and observation-grain ones.
 * Design contract: {@code classifiers/metric_drift/PROGRAM.md}, execution plan {@code PLAN.md} §4.
 *
 * <p><b>No new scheduler, no new job table.</b> Exactly as {@code BehaviorDriftSweep} does it: a
 * metric-drift signal is an ordinary {@code classifier} job whose cursor happens to walk {@code trace} rows,
 * and this class reuses that job's cursor, lease, attempt budget and dead-letter path verbatim.
 * {@link ClassifierWorker} already claims the job; this is one more dispatch branch on {@link Grain}.
 *
 * <p><b>The scored unit is a window of a bucket</b> — not a span, not a turn, not a trace. Individual
 * traces are folded into a running sketch and are never labelled: slow is not bad and expensive is not
 * bad, so there is no per-trace verdict to write, and a detector that wrote one would produce a firing
 * whose every instance needed a human to explain it away (PROGRAM.md §0). What this sweep produces is a
 * closed window compared against the same bucket's own earlier windows.
 *
 * <h2>The two clocks</h2>
 *
 * <p>They are different clocks doing different jobs and collapsing them breaks one or the other.
 *
 * <ul>
 *   <li><b>Windows are cut on EVENT time</b>, {@code COALESCE(started_at, created_at)}, which
 *       {@link TraceHead#eventAt()} carries. A backfill lands a whole corpus in one ingest burst, so by
 *       ingest time "the last hour of traffic" is an artefact of the writer's chunking: one time-cut
 *       window would swallow a month of traffic and compare it against nothing.
 *   <li><b>The keyset cursor stays on INGEST time</b>, {@code trace.created_at}, because that is the
 *       clock that is monotonic and gap-free. A cursor on event time would skip every trace a backfill
 *       delivered out of order, permanently.
 * </ul>
 *
 * <p>Each site below says which clock it is on and why. The per-baseline watermark
 * ({@code counted_through_*}) is on the ingest clock for the same reason the cursor is, and it exists
 * because the cursor lives on the JOB row while the counters live on the baseline: clearing a stuck
 * queue restarts the sweep from a null cursor and would otherwise re-fold samples the sketch already
 * holds. Behaviour drift paid for that lesson in production, reporting {@code trace_count} 565 for a
 * project holding 443 distinct traces.
 *
 * <h2>Settle is not uniform</h2>
 *
 * <p>Cost and the token buckets SUM over a trace's spans and so must wait for every span to arrive;
 * duration is read off a single span that carries its own start and end, so that span's arrival IS the
 * completion signal and a settle horizon buys nothing while delaying every duration finding by its
 * length. {@link MetricDriftConfig#settleSecondsFor} resolves the horizon from the classifier's own measures
 * — one signal, one cursor, so the maximum over them.
 *
 * <h2>Findings, not firings</h2>
 *
 * <p>A closed window that has moved past the floor writes ONE {@code behavior_finding} row per cause, and
 * a shift persisting across twenty windows keeps that one row with a climbing count. No trace is ever
 * labelled: slow is not bad and expensive is not bad, so there is no per-trace claim to make (PROGRAM.md
 * §0). Findings are written <b>unbudgeted</b> by decision (§9) — thresholds get tuned against real
 * firings rather than a guessed number, and the valve goes on escalation, which is the expensive step,
 * rather than on the row.
 *
 * <h2>One pass, one transaction</h2>
 *
 * <p>{@link #sweep} is the transactional unit, for the reason {@code BehaviorSweepCommit} spells out on
 * behaviour drift's equivalent: the counters, the summaries they describe and the cursor that decides
 * what has been counted MUST land together. Here it is not two writes but five — the window rotation,
 * the current sketch, the counter-plus-watermark, the finding row and the job cursor — reaching the
 * database through {@link org.springframework.jdbc.core.simple.JdbcClient}, which autocommits each
 * statement on its own.
 *
 * <p>Without the transaction the gaps between them are all double-counts, because every one of them
 * commits BEFORE the watermark that guards it. A failure after {@code updateCurrentSketch} and before
 * {@code advanceWindow} leaves a sketch holding samples the watermark does not cover, and the retry —
 * reading the same unmoved watermark — folds that whole block in a second time: 160 samples in the
 * sketch against a count of 100, the window closing late, and the doubled block rotating into
 * {@code prev} and, on a fresh bucket, into the pinned reference every later window is judged against.
 * A failure after a mid-page close re-closes the same window and bumps its finding's {@code trace_count}
 * twice. That is behaviour drift's {@code trace_count} 565-against-443 in a new place, and the same
 * answer applies.
 */
@Component
public class MetricDriftSweep implements ClassifierSweep {

    private static final Logger log = LoggerFactory.getLogger(MetricDriftSweep.class);

    /**
     * Traces read per sweep pass. A constant rather than a config dial, deliberately: it is a
     * transport-level bound on one round trip and has no bearing on the operating point, whereas every
     * number in {@link MetricDriftConfig} changes what the detector decides. Sized at the default
     * {@code window_target_count} so a thick bucket closes about one window per pass rather than
     * accumulating a backlog of unclosed windows that all rotate at once.
     */
    static final int PAGE_SIZE = 500;

    /**
     * The two classifiers that file into {@code finding} from here. Named rather than derived, because
     * {@code classifier_key} is the column every shared reader routes on and the mapping from measure to
     * key has to be spelled the same way here and in migration {@code 0086}'s backfill.
     */
    private static final List<String> METRIC_CLASSIFIERS =
            List.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);

    /** Cost measures file under {@code cost_drift}; the two duration measures under {@code duration_drift}. */
    private static String classifierKeyFor(String measure) {
        return Measure.COST.equals(measure) ? BuiltInDetector.Kind.COST_DRIFT : BuiltInDetector.Kind.DURATION_DRIFT;
    }

    private final ClassifierJobRepository jobs;
    private final MetricBaselineRepository baselines;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidenceRefs;
    private final BehaviorSubstrateRepository substrate;
    private final MetricSource source;
    private final ObjectMapper mapper;

    public MetricDriftSweep(
            ClassifierJobRepository jobs,
            MetricBaselineRepository baselines,
            FindingRepository findings,
            FindingEvidenceRepository evidenceRefs,
            BehaviorSubstrateRepository substrate,
            MetricSource source,
            ObjectMapper mapper) {
        this.jobs = jobs;
        this.baselines = baselines;
        this.findings = findings;
        this.evidenceRefs = evidenceRefs;
        this.substrate = substrate;
        this.source = source;
        this.mapper = mapper;
    }

    /**
     * What one metric sweep did, including the one number only this classifier has.
     *
     * <p>Richer than the seam's {@link SweepOutcome} on purpose. {@code windowsClosed} is what
     * describes progress HERE — a pass can scan four hundred traces, fire nothing, and still have done
     * its whole job by rotating two windows — and it means nothing for a trace-grain drift sweep or for
     * tool errors. So it stays on this type, {@link #sweep(SweepContext)} narrows at the seam, and the
     * shared record does not grow a field per classifier. The integration suite asserts on it directly
     * through {@link #sweepMetrics}.
     *
     * @param fired findings written or bumped this pass — one per closed window that moved past the
     *     floor, never one per trace.
     * @param windowsClosed windows rotated this pass, the number that actually describes progress here.
     */
    public record MetricSweepOutcome(int scanned, int fired, int windowsClosed) {

        static final MetricSweepOutcome EMPTY = new MetricSweepOutcome(0, 0, 0);

        /** The two numbers the worker's completion line prints. */
        SweepOutcome narrowed() {
            return new SweepOutcome(scanned, fired);
        }
    }

    /**
     * Both windowed metric classifiers, from one bean. They share a baseline store, a config record and
     * a fold, and differ only in which measures they read — splitting them into two beans to satisfy a
     * one-kind-per-sweep port would have bought nothing and doubled the fold.
     *
     * <p>Declaring both kinds EXPLICITLY is also what let the worker's old {@code else} arm go. That arm
     * routed every unrecognised WINDOW kind here, so a classifier with no dispatch of its own silently
     * ran metric drift instead — which is exactly what {@code tool_error} did once, keeping a second copy
     * of every duration and cost baseline behind a green build.
     */
    @Override
    public Set<String> kinds() {
        return Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);
    }

    /**
     * The seam entry point: run one pass and report the two numbers every sweep can answer.
     *
     * <p>{@code @Transactional} is on BOTH this method and {@link #sweepMetrics}, deliberately. This one
     * needs it because the call arriving from the worker lands here, and Spring's proxy only wraps the
     * method it is invoked THROUGH — the self-invocation below bypasses the proxy entirely, so an
     * annotation on {@code sweepMetrics} alone would leave every worker-driven pass running with no
     * transaction at all and re-open every double-count the class comment lists. {@code sweepMetrics}
     * keeps its own because the integration suite calls it directly and needs the same guarantee. Two
     * annotations, one transaction: the inner call joins the outer.
     */
    @Transactional
    @Override
    public SweepOutcome sweep(SweepContext ctx) {
        return sweepMetrics(ctx.job(), ctx.classifier()).narrowed();
    }

    /**
     * Run one metric-drift sweep for a claimed signal job, advancing its cursor.
     *
     * <p>The whole pass is one transaction — see the class comment for the double-counts the gaps
     * between its writes would otherwise be. The unit ends at {@code markSwept}: baselines, findings and
     * the cursor commit together, so a retry either re-does a page nothing recorded or skips one
     * everything recorded, and never the half-and-half in between.
     */
    @Transactional
    public MetricSweepOutcome sweepMetrics(ClassifierJobRow job, ClassifierRow signal) {
        MetricDriftConfig config = MetricDriftConfig.of(mapper, signal.configJson());
        List<Measured> live = config.measured();
        if (live.isEmpty()) {
            // A signal whose blob names no measure this build knows. Finish the job rather than failing
            // it: the cursor must not advance over traffic nothing measured, and a config edit fixes it.
            StructuredLog.info(log, Markers.OPS, "metric.sweep.no-measures")
                    .field("project", job.projectId())
                    .field("signal", signal.classifierKey())
                    .log();
            jobs.markSwept(job.id(), null, null);
            return MetricSweepOutcome.EMPTY;
        }

        // EVENT clock. The cursor is (trace.started_at, trace.id) — the same keyset the span- and
        // trace-grain sweeps use, so this classifier's job row needs no new columns. There is no per-measure
        // settle horizon any more: trace.is_settled is a fact about the trace rather than a guess scaled
        // per measure, and a measure that genuinely needs nothing settled (duration_drift) was already
        // paying the cursor's monotonicity for the ones that do.
        List<TraceHead> heads = substrate.tracesAfter(job.projectId(), job.cursorAt(), job.cursorId(), PAGE_SIZE);
        if (heads.isEmpty()) {
            jobs.markSwept(job.id(), null, null);
            return MetricSweepOutcome.EMPTY;
        }

        String now = Instant.now().toString();
        MetricSource.Tally tally = new MetricSource.Tally();
        Folded folded = Folded.NONE;

        // Read once for the whole page rather than per bucket. A project sweeps thousands of buckets and
        // the confirmed set is a handful, so this is a map built once and consulted in memory — see
        // FindingRepository#confirmedSpansBySubject for why the exclusion is decided here, on
        // the read, rather than when the day was folded.
        Map<String, List<ConfirmedSpan>> confirmed =
                findings.confirmedSpansBySubject(job.projectId(), METRIC_CLASSIFIERS);

        List<Measured> turnMeasures = config.measuredAt(Grain.TURN);
        int held = 0;
        if (!turnMeasures.isEmpty()) {
            List<TurnMetrics> turns = source.turnMetrics(job.projectId(), heads, tally);
            int admissible = admissibleThrough(heads, turns, config);
            held = heads.size() - admissible;
            if (admissible == 0) {
                // The whole page is still arriving. Finish the job WITHOUT moving the cursor, so the next
                // tick re-offers these traces rather than the sweep stepping over turns it cannot read.
                jobs.markSwept(job.id(), null, null);
                return MetricSweepOutcome.EMPTY;
            }
            if (held > 0) {
                heads = heads.subList(0, admissible);
                turns = turns.subList(0, admissible);
                // The tally already counted the held turns' abstentions. Left as counted rather than
                // re-derived: a NO_ROOT_SPAN that will be swept again next tick is exactly the transient
                // this gate exists to make visible, and `heldForRoot` below says how many.
            }
            for (Measured spec : turnMeasures) {
                folded = folded.plus(foldMeasure(
                        job.projectId(), signal, config, spec, turnSamples(heads, turns, spec), confirmed, now));
            }
        }

        List<Measured> spanMeasures = config.measuredAt(Grain.OBSERVATION);
        if (!spanMeasures.isEmpty()) {
            // Grouped once and folded per measure, which is exact only while a span carries ONE reading —
            // MetricSource.ToolMetrics has a single `duration` field and tool_duration is the only measure
            // at this grain. A second span-grain measure would have to select its own value per span, the
            // way turnSamples takes a spec and reads that measure off the turn.
            Map<Bucket, List<Sample>> byTool = toolSamples(heads, source.toolMetrics(job.projectId(), heads, tally));
            for (Measured spec : spanMeasures) {
                folded = folded.plus(foldMeasure(job.projectId(), signal, config, spec, byTool, confirmed, now));
            }
        }

        // Both grains are folded before ANY finding is written, which is the whole shape of the §6.1
        // suppression rule: a turn shift is a finding only if no tool shift inside that call site
        // accounts for it, and the tool shift that does account for it has to carry it as evidence.
        int fired = emit(job.projectId(), signal, config, folded.pending());

        // The cursor advances over the WHOLE page, including heads no measure could read (an unattributed
        // trace, an unfinished turn). A cursor that advanced only over scored rows would re-offer the rest
        // on every pass forever.
        TraceHead last = heads.get(heads.size() - 1);
        jobs.markSwept(job.id(), last.eventAt(), last.traceId());

        // Logged whether or not anything closed. A measure abstaining on 100% of traffic never fires and
        // looks identical to a quiet week in the findings; only these counters tell them apart, which is
        // the failure PROGRAM.md §13 opens with.
        StructuredLog.info(log, Markers.OPS, "metric.sweep.windows")
                .field("project", job.projectId())
                .field("signal", signal.classifierKey())
                .field("scanned", heads.size())
                // How many trailing traces this pass declined to step over because their root span had
                // not landed yet. Steadily non-zero is normal (it is the newest edge of the page);
                // steadily equal to PAGE_SIZE means roots are not arriving at all, which is an ingest
                // fault this line is the only place that names.
                .field("heldForRoot", held)
                .field("windowsClosed", folded.closed())
                .field("findings", fired)
                .field("measures", tally.summary())
                .log();
        return new MetricSweepOutcome(heads.size(), fired, folded.closed());
    }

    /**
     * Windows rotated and findings EARNED by one fold, summed across measures and buckets.
     *
     * <p>Earned, not written. Folding is where a window closes and a comparison decides; writing happens
     * once, after both grains have been folded, because the §6.1 suppression rule cannot be evaluated
     * until it can see both — a turn shift is only a finding if no tool shift explains it, and the tool
     * shift that explains it has to carry it as evidence.
     */
    private record Folded(int closed, List<Pending> pending) {

        static final Folded NONE = new Folded(0, List.of());

        Folded plus(Folded other) {
            if (other.pending.isEmpty()) return new Folded(closed + other.closed, pending);
            if (pending.isEmpty()) return new Folded(closed + other.closed, other.pending);
            List<Pending> merged = new ArrayList<>(pending.size() + other.pending.size());
            merged.addAll(pending);
            merged.addAll(other.pending);
            return new Folded(closed + other.closed, List.copyOf(merged));
        }
    }

    /**
     * A finding one closed window earned, held until the whole page has been folded at both grains.
     *
     * @param callSites how many of the window's fresh samples came in through each entry point. At turn
     *     grain that is one key; at tool grain it is however many call sites dispatched the tool. Read
     *     twice — for the call site the finding row is filed under, and by {@link MetricSuppression} to
     *     decide whether this tool shift was measured over the traffic whose turns moved.
     * @param memberRefs every row of the closed window, at the grain the measure is scored at — the
     *     flagged population, written verbatim as {@code member} evidence when this earns a finding.
     * @param baselineRefs the rows the REFERENCE was fitted over, or empty when it was not fitted over
     *     rows anybody kept. The pinned arm has them, because {@code repin} stores the window it pinned;
     *     the rolling-control arm does not, because that reference is a weighted ring of per-day
     *     histograms rather than one window, and there is no set of rows it corresponds to.
     */
    private record Pending(
            Measured spec,
            Bucket bucket,
            MetricBaselineRow row,
            Decision decision,
            MetricSketch refSketch,
            @Nullable MetricWorkload refWorkload,
            @Nullable MetricTokens refTokens,
            Window closedWindow,
            @Nullable String sinceVersionId,
            @Nullable String windowOpenedAt,
            Sample last,
            Map<String, Long> callSites,
            List<FindingEvidenceRepository.Ref> memberRefs,
            List<FindingEvidenceRepository.Ref> baselineRefs,
            @Nullable Resolved control) {}

    // -----------------------------------------------------------------------------------------------
    // Candidates
    // -----------------------------------------------------------------------------------------------

    /**
     * How much of the page may be swept now: everything up to the first trace whose ROOT SPAN has not
     * arrived and is still young enough that it plausibly will. The rest is left for the next tick.
     *
     * <p><b>This is not the settle window, and it must not become one.</b> PROGRAM.md §5 is right that
     * duration needs no settle horizon — the root span carries its own start and end, so its arrival IS
     * the completion signal and delaying every reading by a fixed horizon would buy nothing. What §5
     * assumes, and what nothing else here provides, is that the sweep waits for that arrival. The
     * {@code trace} row is created by the FIRST span to land, and a batch exporter flushes on span END,
     * so the root — which outlives every child — routinely ships in a later request than the children
     * that created the row (the §7.1 trace timer says the same: it folds a later batch's earlier start
     * into {@code started_at} but never moves {@code created_at}, so the keyset position does not move). A trace read in that gap has no root, abstains with {@code NO_END_TIME}, and — because the
     * cursor is forward-only — is never offered again.
     *
     * <p>Reading "no root" honestly in that gap is half the fix and lives one layer down. Ingest inserts
     * a child whose parent has not landed with a NULL {@code parent_observation_id} and the producer's
     * stated parent kept verbatim in {@code parent_external_span_id}, so without the clause
     * {@code MetricSourceRepository.turnFacts} now carries, the earliest such child passes for the root
     * and a 30-second turn reports the 1-second child that happened to flush first — a reading, not an
     * abstention, and one this gate would never have seen.
     *
     * <p><b>The dropout would be length-biased, which is what makes it corrosive rather than lossy.</b>
     * The gap between the trace row and its root is the turn's own duration, so the probability of
     * losing a turn rises monotonically with how long it took. The sketch would be fitted on a
     * fast-biased sample, and a regression that lengthens turns would push more of the affected traffic
     * OUT of the population instead of into its tail — the detector going quieter as the regression got
     * worse. Tool spans are unaffected (children arrive early), so the two grains of one classifier
     * would also be sampling different populations, silently skewing the §6.1 comparison.
     *
     * <p>So the wait is conditional, not blanket: a trace whose root is already there is swept on the
     * pass it appears in, exactly as §5 wants. Only a rootless one waits, and only up to
     * {@link MetricDriftConfig#settleSeconds} — the backstop that keeps a trace whose root will NEVER
     * arrive (a producer that emits no parentless span, a root lost in transit) from stalling the cursor
     * for good. Past it the trace is admitted and abstains, which is the honest reading.
     *
     * <p>The age is measured on the EVENT clock, {@code COALESCE(started_at, created_at)}: for a
     * rootless trace that is its earliest child's start, i.e. roughly when the turn began, which is the
     * quantity the backstop is actually about. A stamp that cannot be read, or one in the future, does
     * not hold the page — an unbounded hold on an unreadable clock is the one outcome worse than the
     * abstention.
     *
     * @return the number of leading heads that may be folded; {@code heads.size()} when nothing is held
     */
    private static int admissibleThrough(List<TraceHead> heads, List<TurnMetrics> turns, MetricDriftConfig config) {
        Instant now = Instant.now();
        long backstop = Math.max(0, config.settleSeconds());
        int n = Math.min(heads.size(), turns.size());
        for (int i = 0; i < n; i++) {
            if (turns.get(i).completion() != MetricSource.Completion.NO_ROOT_SPAN) continue;
            if (stillArriving(heads.get(i).eventAt(), now, backstop)) return i;
        }
        // Fewer facts than heads is not a shape this produces (turnFacts returns one row per trace in the
        // page), but if it ever did, the unmatched tail has been read by nothing and is held rather than
        // stepped over.
        return n < heads.size() ? n : heads.size();
    }

    /** Whether a rootless trace is young enough that its root may still be in flight. */
    private static boolean stillArriving(String eventAt, Instant now, long backstopSeconds) {
        try {
            Duration age = Duration.between(Instant.parse(eventAt), now);
            return !age.isNegative() && age.getSeconds() < backstopSeconds;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * One reading, carried with both clocks and the deploy it ran under.
     *
     * @param createdAt INGEST time, and {@code traceId} with it: the pair the per-baseline watermark
     *     compares against, matching the job cursor's keyset exactly.
     * @param eventAt EVENT time — what windows are cut on.
     * @param logValue {@code ln} of the measure. Taken once, at the source, because W₁ on logs is what
     *     makes {@code e^W₁} the multiplicative shift a finding reports.
     * @param workload what the USER asked for on this turn, folded into the window's workload sketches
     *     beside the measure. Evidence for the finding, never a covariate of the measure: normalizing
     *     duration on the agent's own choices would explain the bug away (PROGRAM.md §3.2).
     * @param callSiteId the entry point the trace this reading came from was resolved to. At turn grain
     *     it is the bucket key itself; at tool grain it is not, because a tool bucket is keyed on an
     *     {@code ActionSymbol} alone and one tool's window legitimately draws from several call sites.
     *     Carried per sample rather than per bucket so a tool finding can be filed under the entry point
     *     most of its window actually came through, and so {@link MetricSuppression} can ask whether a
     *     tool shift was measured over the traffic of the call site whose turns moved.
     * @param tokens what this turn's dollars were made of, or null for a measure that has no dollars.
     *     Folded only under {@code cost}: the decomposition is the explanation a cost finding carries
     *     (PROGRAM.md §6.1), and summarizing it beside a duration would cost a blob per bucket to explain
     *     nothing. Null here is "this measure has no such quantity", distinct from a null INSIDE
     *     {@link TokenReadings}, which is "the provider did not report this bucket".
     */
    private record Sample(
            String traceId,
            /**
             * The span this reading came off, at span grain, and null at turn grain — the grain the
             * finding's evidence refs are written at, so the population a claim rests on is enumerated
             * at the same resolution the claim was computed at. Never read by the statistic.
             */
            @Nullable String spanId,
            /**
             * The conversation the trace belongs to, carried for exactly one reason: a Layer-2
             * escalation is pointed at an exemplar, and the triage agent reads the whole thread rather
             * than one turn in isolation. Never read by the statistic — a window is a population, and
             * which conversation a sample came from says nothing about how long it took.
             */
            String contextId,
            String createdAt,
            String eventAt,
            @Nullable String projectVersionId,
            String callSiteId,
            double logValue,
            MetricSource.Workload workload,
            @Nullable TokenReadings tokens) {}

    /**
     * A baseline's scope below the (signal, measure) it belongs to — exactly the columns
     * {@code metric_baseline_scope} is unique on, and deliberately nothing else.
     *
     * <p>The call site is <b>not</b> here. At turn grain it would be redundant with {@code key}; at tool
     * grain it would be wrong, and wrong in a way that corrupts rather than merely over-partitions —
     * two buckets differing only in call site resolve through {@code ensure} to the SAME row, so the
     * second fold of a page would re-read that row's watermark before the first fold's write and count
     * its samples in twice.
     */
    private record Bucket(String key) {}

    /**
     * Group one page's turn-grain readings for one measure into its buckets.
     *
     * <p>{@code turnMetrics} returns one reading per head in head order, so the two lists are zipped by
     * index — the head carries the ingest clock and the reading carries the value.
     *
     * <p><b>{@code __unattributed__} is not a bucket</b> (PROGRAM.md §2.4). Behaviour drift lumps those
     * traces together, which is right for a sequence model and wrong here: the pile is a mixture of
     * everything the instrumentation missed, so its distribution moves whenever the mix moves and every
     * finding on it would be an artefact. The actionable fact about that pile is its SIZE, which belongs
     * in {@code vitals/} as a coverage number rather than here as a distribution.
     */
    private static Map<Bucket, List<Sample>> turnSamples(
            List<TraceHead> heads, List<TurnMetrics> turns, Measured spec) {
        Map<Bucket, List<Sample>> byBucket = new LinkedHashMap<>();
        for (int i = 0; i < heads.size() && i < turns.size(); i++) {
            TraceHead head = heads.get(i);
            TurnMetrics turn = turns.get(i);
            if (BehaviorSubstrateRepository.UNATTRIBUTED.equals(turn.callSiteId())) continue;
            if (!(turn.measurement(spec.measure()) instanceof Measurement.Present value)) continue;
            Bucket bucket = new Bucket(turn.callSiteId());
            byBucket.computeIfAbsent(bucket, k -> new ArrayList<>())
                    .add(new Sample(
                            head.traceId(),
                            // Turn grain: the reading is a property of the whole run, so the trace IS
                            // the row that was measured.
                            null,
                            head.subjectSessionId(),
                            head.eventAt(),
                            head.eventAt(),
                            head.projectVersionId(),
                            turn.callSiteId(),
                            value.logValue(),
                            turn.workload(),
                            // Only the cost window carries the decomposition, because only the cost
                            // finding is explained by it. Reading it off the same TurnMetrics the measure
                            // came from is what guarantees the two describe one turn.
                            Measure.COST.equals(spec.measure()) ? turn.tokenReadings() : null));
        }
        return byBucket;
    }

    /**
     * Group one page's dispatchable spans into their tool buckets — the {@code tool_duration} candidates.
     *
     * <p><b>Walked in HEAD order, not in query order.</b> The per-baseline watermark is a keyset on
     * {@code (trace.created_at, trace.id)}, so samples have to be folded in that order or a later fold
     * would set a watermark that excludes an earlier trace's spans forever. {@code toolSpanFacts} orders
     * by {@code trace_id}, which correlates with ingest time (ULIDs) but is not the same ordering, so the
     * heads — which came out of the keyset query itself — are what the walk follows.
     *
     * <p><b>The bucket is the tool, not the tool-within-a-call-site.</b> A tool's latency is a tool's
     * latency whichever entry point dispatched it, and scoping the bucket per call site would shatter a
     * shared tool into as many populations as it has callers, none of them thick enough to arm. Which
     * call site a finding is FILED under is decided per closed window, from the traffic that filled it.
     *
     * <p><b>Unattributed traces contribute here</b>, unlike at turn grain. §2.4's objection is that the
     * {@code __unattributed__} pile is a MIXTURE whose distribution moves whenever the mix does — true of
     * a call-site bucket assembled from it, and not true of {@code tool:search_docs}, which is one tool
     * however the trace that called it was tagged. Dropping those spans would thin exactly the buckets
     * least likely to arm.
     *
     * <p><b>No workload is folded.</b> {@link MetricSource.Workload#NONE} throughout, deliberately: a
     * tool call has no prompt of its own, and folding the enclosing turn's workload once per tool call
     * would weight it by how often the agent chose to call that tool — conditioning on the answer, which
     * is the one thing PROGRAM.md §3.2 forbids. A tool finding's evidence therefore prints its workload
     * pairs as nulls, which reads as "no such quantity" rather than as a workload of zero.
     */
    private static Map<Bucket, List<Sample>> toolSamples(List<TraceHead> heads, List<MetricSource.ToolMetrics> tools) {
        Map<String, List<MetricSource.ToolMetrics>> byTrace = new LinkedHashMap<>();
        for (MetricSource.ToolMetrics tool : tools) {
            byTrace.computeIfAbsent(tool.traceId(), k -> new ArrayList<>()).add(tool);
        }

        Map<Bucket, List<Sample>> byBucket = new LinkedHashMap<>();
        for (TraceHead head : heads) {
            for (MetricSource.ToolMetrics tool : byTrace.getOrDefault(head.traceId(), List.of())) {
                if (!(tool.duration() instanceof Measurement.Present value)) continue;
                Bucket bucket = new Bucket(tool.bucketKey());
                byBucket.computeIfAbsent(bucket, k -> new ArrayList<>())
                        .add(new Sample(
                                // The TRACE's ingest keyset, not the span's: the watermark has to line up
                                // with the job cursor, and the cursor walks traces. Every span of a trace
                                // therefore shares one watermark position and they are folded together or
                                // not at all, which is what makes a replayed page idempotent here too.
                                head.traceId(),
                                tool.observationId(),
                                head.subjectSessionId(),
                                head.eventAt(),
                                // EVENT time is the SPAN's own start, though — a window is a stretch of the
                                // agent's timeline, and a tool called an hour into a long trace belongs in
                                // the window that hour falls in.
                                tool.eventAt(),
                                head.projectVersionId(),
                                tool.callSiteId(),
                                value.logValue(),
                                MetricSource.Workload.NONE,
                                // No decomposition either: a tool span's duration is not made of tokens.
                                null));
            }
        }
        return byBucket;
    }

    // -----------------------------------------------------------------------------------------------
    // Folding
    // -----------------------------------------------------------------------------------------------

    /** Fold one measure's whole page into its buckets. */
    private Folded foldMeasure(
            String projectId,
            ClassifierRow signal,
            MetricDriftConfig config,
            Measured spec,
            Map<Bucket, List<Sample>> byBucket,
            Map<String, List<ConfirmedSpan>> confirmed,
            String now) {
        Folded folded = Folded.NONE;
        for (Map.Entry<Bucket, List<Sample>> e : byBucket.entrySet()) {
            folded = folded.plus(foldBucket(projectId, signal, config, spec, e.getKey(), e.getValue(), confirmed, now));
        }
        return folded;
    }

    /**
     * One window slot's three summaries over ONE set of samples: what the agent did, what the user asked
     * for, and — under {@code cost} alone — what the dollars were made of. Carried together everywhere so
     * they can never come from different sets of turns, which is the one way the
     * flat-inputs-versus-moved-outputs argument could silently become false.
     *
     * <p>Every window carries all three slots and the sidecars stay empty where they do not apply; an
     * empty sidecar serializes to nothing at all ({@link #workloadJson()} / {@link #tokensJson()} return
     * null), so a tool window costs no blob for a decomposition it has no dollars to decompose.
     */
    private record Window(MetricSketch measure, MetricWorkload workload, MetricTokens tokens) {

        Window(Grid grid) {
            this(
                    new MetricHistogram(grid),
                    new MetricWorkload(MetricWorkload.grid(grid.bins())),
                    new MetricTokens(MetricTokens.grid(grid.bins())));
        }

        void add(Sample sample) {
            measure.add(sample.logValue());
            workload.add(
                    sample.workload().inputTokens(),
                    sample.workload().userMsgChars(),
                    sample.workload().priorTurns());
            TokenReadings reading = sample.tokens();
            if (reading != null) {
                tokens.add(
                        reading.input(),
                        reading.output(),
                        reading.cacheRead(),
                        reading.cacheWrite(),
                        reading.cacheReadPct());
            }
        }

        Window copy() {
            return new Window(measure.copy(), workload.copy(), tokens.copy());
        }

        /** The workload's serialized form, or null when nothing on this window reported any workload. */
        @Nullable
        String workloadJson() {
            return workload.isEmpty() ? null : workload.toJson();
        }

        /** The decomposition's serialized form, or null — which every non-cost window returns. */
        @Nullable
        String tokensJson() {
            return tokens.isEmpty() ? null : tokens.toJson();
        }
    }

    /**
     * The pinned reference as it stands, threaded through the close loop rather than re-read: a page can
     * close several windows and the first close of a fresh bucket establishes the pin the second is
     * compared against.
     *
     * @param versionId the deploy this reference hangs on, and what a finding against it reports as
     *     {@code since_version_id} — a shift measured against the window pinned at a deploy is a shift
     *     since THAT deploy, not since whichever version the current window happened to run under.
     */
    private record Pinned(
            @Nullable MetricSketch sketch,
            @Nullable MetricWorkload workload,
            @Nullable MetricTokens tokens,
            /** The rows that window was fitted over — the baseline evidence a finding against it writes. */
            List<FindingEvidenceRepository.Ref> refs,
            @Nullable String versionId) {}

    /**
     * Fold one bucket's slice of the page into its current window, closing and comparing whenever the
     * window's criteria are met — possibly more than once, since a page can be wider than a window.
     */
    private Folded foldBucket(
            String projectId,
            ClassifierRow signal,
            MetricDriftConfig config,
            Measured spec,
            Bucket bucket,
            List<Sample> samples,
            Map<String, List<ConfirmedSpan>> confirmed,
            String now) {
        MetricBaselineRow row = baselines.ensure(seed(projectId, signal.id(), spec, bucket, now));
        List<Sample> fresh = notYetCounted(row, samples);
        if (fresh.isEmpty()) return Folded.NONE;

        Grid grid = spec.grid();
        String gridId = new MetricHistogram(grid).gridId();
        // The sweep's wall clock as an instant. The control's day keys, weights and retention are all
        // measured against it, and deriving it from the same string the rest of this method writes is
        // what keeps the ring's ages and the row's timestamps from drifting apart.
        Instant closedAt = Instant.parse(now);
        MetricControl control = MetricControl.fromJson(row.controlJson());
        // The days a confirmed regression ran through, resolved from this baseline's own findings. Held
        // for the whole page: a page can close several windows and every one of them is judged against a
        // control excluding the same days, which is what makes the page's outcome independent of where
        // its boundaries happened to fall.
        Set<String> excludedDays = excludedDays(confirmed.get(row.id()));
        Pinned pinned = new Pinned(
                rehydrate(row.pinnedSketchJson(), row.id()),
                rehydrateWorkload(row.pinnedWorkloadJson(), grid, row.id()),
                rehydrateTokens(row.pinnedTokensJson(), grid, row.id()),
                MetricEvidenceRefs.fromJson(row.pinnedRefsJson()),
                row.pinnedByVersionId());
        Window persisted = rehydrateWindow(
                row.currentSketchJson(), row.currentWorkloadJson(), row.currentTokensJson(), grid, row.id());

        Window current;
        long count;
        String openedAt;
        String state = row.state();
        // True once a write has zeroed the row's current_count, which decides whether the closing
        // advanceWindow adds the page's whole contribution or only the part after the last rotation.
        boolean countZeroed = false;
        if (persisted != null && persisted.measure().gridId().equals(gridId)) {
            current = persisted;
            count = row.currentCount();
            openedAt = row.currentOpenedAt();
        } else {
            // Either nothing has been written yet, or hist_bins was edited under a live project and the
            // partial window sits on a layout the new samples cannot join. The count, the open time and
            // the sketch describe one window, so all three restart together. The control ring is left
            // alone rather than cleared: its day slots on the dead grid are skipped by
            // MetricControl#resolve and age out on their own, and clearing it would throw away the days
            // that ARE readable if the grid is later changed back. The pinned reference is
            // re-established by the first close below, which detects its own mismatch.
            current = new Window(grid);
            count = 0;
            openedAt = null;
            if (persisted != null) {
                baselines.closeWindow(row.id(), row.controlJson(), null, null, null, null, 0, now);
                countZeroed = true;
            }
        }

        // EVENT clock. The open time is the event time of the window's FIRST sample, so a window measures
        // a span of the agent's real timeline rather than a span of the exporter's.
        String openTimeForWrite = null;
        int closed = 0;
        List<Pending> pending = new ArrayList<>();
        // Which entry points this window's fresh samples came in through. One key at turn grain; at tool
        // grain as many as dispatched the tool. Cleared with the window, because it describes THAT window.
        Map<String, Long> callSites = new LinkedHashMap<>();
        // Every row folded into the window, in fold order — the population a finding on it would be a
        // claim about. Held only for the window being filled: the sketch is what survives a rotation,
        // and the refs of a window that earned no finding are of no interest to anyone.
        List<FindingEvidenceRepository.Ref> windowRefs = new ArrayList<>();
        for (Sample sample : fresh) {
            if (openedAt == null) {
                openedAt = sample.eventAt();
                openTimeForWrite = sample.eventAt();
            }
            current.add(sample);
            callSites.merge(sample.callSiteId(), 1L, Long::sum);
            windowRefs.add(refOf(sample));
            count++;
            if (!shouldClose(count, openedAt, sample.eventAt(), config)) continue;

            Window closedWindow = current.copy();
            // Compared BEFORE the fold, always. The control is what this bucket looked like BEFORE this
            // window, and folding first would compare the window against a reference that already
            // contains it — on a thin bucket, where one window is a large share of a day, that alone
            // would drag the bar most of the way to the window and hide the shift.
            MetricControl.Resolved reference = control.resolve(grid, closedAt, excludedDays);
            Compared compared = compareAndPin(
                    projectId,
                    signal,
                    config,
                    spec,
                    bucket,
                    row,
                    reference,
                    pinned,
                    closedWindow,
                    openedAt,
                    sample,
                    Map.copyOf(callSites),
                    List.copyOf(windowRefs));
            control = control.fold(
                    grid,
                    // The day the window CLOSED, on the wall clock, so the ring's ages line up with the
                    // clock its weights are measured against. Not the window's event span, which a
                    // backfill puts months in the past.
                    MetricControl.dayOf(closedAt),
                    closedWindow.measure(),
                    closedWindow.workload(),
                    closedWindow.tokens(),
                    closedAt);
            baselines.closeWindow(row.id(), control.toJson(), null, null, null, null, 0, now);
            pinned = compared.pinned();
            if (compared.pending() != null) pending.add(compared.pending());
            callSites = new LinkedHashMap<>();
            windowRefs = new ArrayList<>();
            if (!MetricBaselineRow.State.ARMED.equals(state)
                    && closedWindow.measure().count() >= config.minSample()) {
                // Arming is a decision about the row, kept out of advanceWindow on purpose: the sweep must
                // not be able to arm a bucket as a side effect of counting traffic into it.
                baselines.updateState(row.id(), MetricBaselineRow.State.ARMED, now);
                state = MetricBaselineRow.State.ARMED;
            }
            current = new Window(grid);
            count = 0;
            openedAt = null;
            openTimeForWrite = null;
            countZeroed = true;
            closed++;
        }

        // The tail: whatever is left in the open window after the last rotation, or the whole page when
        // nothing rotated.
        boolean empty = current.measure().count() == 0;
        baselines.updateCurrentSketch(
                row.id(),
                empty ? null : current.measure().toJson(),
                empty ? null : current.workloadJson(),
                empty ? null : current.tokensJson(),
                now);
        Sample last = fresh.get(fresh.size() - 1);
        baselines.advanceWindow(
                row.id(),
                countZeroed ? count : fresh.size(),
                now,
                openTimeForWrite,
                maxEventAt(fresh),
                // INGEST clock, and the whole reason the watermark is not the job cursor: it shares the
                // counters' lifetime, so a re-swept page cannot fold the same trace in twice.
                last.createdAt(),
                last.traceId());
        return new Folded(closed, List.copyOf(pending));
    }

    /**
     * The UTC days a confirmed regression on this bucket ran through — the days the rolling control must
     * leave out.
     *
     * <p>Every day the spell touched, not just the day it opened. A regression that ran for a week was
     * not normal on any of those days, and excluding only its first would let the rest of it become the
     * bar it is being measured against.
     *
     * <p>A span whose bounds will not parse excludes NOTHING rather than everything. The failure this
     * guards is silent and total: an unreadable timestamp that excluded every day would leave the control
     * empty, the comparison would fall silent with {@code NO_REFERENCE}, and a bucket would simply stop
     * being watched with nothing in the logs saying so.
     */
    private static Set<String> excludedDays(@Nullable List<ConfirmedSpan> spans) {
        if (spans == null || spans.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (ConfirmedSpan span : spans) {
            LocalDate from;
            LocalDate to;
            try {
                from = LocalDate.ofInstant(Instant.parse(span.fromAt()), ZoneOffset.UTC);
                to = LocalDate.ofInstant(Instant.parse(span.toAt()), ZoneOffset.UTC);
            } catch (RuntimeException e) {
                continue;
            }
            if (to.isBefore(from)) continue;
            // Bounded by the ring's own retention: a spell running for a year would otherwise walk a year
            // of dates to exclude days the control stopped holding weeks ago.
            LocalDate floor = to.minusDays(MetricControl.RETAIN_DAYS);
            for (LocalDate d = from.isBefore(floor) ? floor : from; !d.isAfter(to); d = d.plusDays(1)) {
                out.add(d.toString());
            }
        }
        return out;
    }

    /**
     * What one close produced: the pinned reference as it now stands, and the finding this window earned,
     * or null when it earned none.
     */
    private record Compared(Pinned pinned, @Nullable Pending pending) {}

    /**
     * Run both comparisons for a just-closed window, earn at most one finding, and return the pinned
     * reference as it now stands.
     *
     * <p>Both references run on every close because each is blind in one direction alone: the rolling
     * control catches sudden breaks and never notices a slow boil, the pinned window catches cumulative
     * creep and then screams forever once something legitimately changed (PROGRAM.md §4.3).
     *
     * <p><b>One window, at most one finding, and the pinned view wins.</b> Two references are two views
     * of one bucket's one window, not two events, and reporting both would say the same thing twice in a
     * stream that has no alert budget to absorb it (§6.1: one event, one finding). The pinned comparison
     * is preferred because it is the one a human can act on — <em>Legitimate — absorb</em> moves the
     * pinned reference — and because it is the one a human can settle. The control comparison still opens
     * a finding on its own when the pinned one is silent, which is the recovery case (a bucket that broke
     * and came back to its pinned level) and the case where nothing has been pinned yet.
     *
     * <p>A step change no longer fires against the short-horizon reference exactly once. That WAS true
     * when the reference was the previous window, because the next window's previous is the new level;
     * the rolling control fades a change out over a fortnight instead, and holds a CONFIRMED regression
     * out of itself indefinitely, so the finding persists for as long as the regression does.
     *
     * <p><b>Pinning here is a bootstrap, never an absorb.</b> A bucket with no pinned reference has
     * nothing to compare against, so its first armed close establishes one; likewise a pinned reference
     * stranded on a dead grid can never answer again and is replaced. Neither is the
     * <em>Legitimate — absorb</em> write, which moves a LIVE reference and is reachable only from a human
     * pressing the verb — {@code BehaviorTriageVerdict} states that Layer-2's confidence is read
     * "never as authority to mutate the baseline", because an automatic re-pin would let the very next
     * window silently normalize a real regression.
     */
    private Compared compareAndPin(
            String projectId,
            ClassifierRow signal,
            MetricDriftConfig config,
            Measured spec,
            Bucket bucket,
            MetricBaselineRow row,
            MetricControl.@Nullable Resolved control,
            Pinned pinned,
            Window closedWindow,
            @Nullable String windowOpenedAt,
            Sample last,
            Map<String, Long> callSites,
            List<FindingEvidenceRepository.Ref> windowRefs) {
        MetricSketch cur = closedWindow.measure();
        Decision againstPrevious = MetricDriftDetector.decide(
                spec.measure(), Reference.PREVIOUS, control == null ? null : control.measure(), cur, config);
        report(projectId, signal, bucket, againstPrevious);

        Decision againstPinned =
                MetricDriftDetector.decide(spec.measure(), Reference.PINNED, pinned.sketch(), cur, config);
        report(projectId, signal, bucket, againstPinned);

        Pending pending = null;
        if (againstPinned.fired() && pinned.sketch() != null) {
            pending = new Pending(
                    spec,
                    bucket,
                    row,
                    againstPinned,
                    pinned.sketch(),
                    pinned.workload(),
                    pinned.tokens(),
                    closedWindow,
                    pinned.versionId(),
                    windowOpenedAt,
                    last,
                    callSites,
                    windowRefs,
                    pinned.refs(),
                    null);
        } else if (againstPrevious.fired() && control != null) {
            pending = new Pending(
                    spec,
                    bucket,
                    row,
                    againstPrevious,
                    control.measure(),
                    control.workload(),
                    control.tokens(),
                    closedWindow,
                    last.projectVersionId(),
                    windowOpenedAt,
                    last,
                    callSites,
                    windowRefs,
                    // The rolling control is a weighted merge of per-day histograms over up to
                    // MetricControl.RETAIN_DAYS days. There is no window behind it whose rows could be
                    // enumerated, so this arm's claim carries a member set and no baseline one, and the
                    // finding's per-role counts say which arm it was. What it compared against is
                    // described on the finding instead — see the `control` block.
                    List.of(),
                    control);
        }

        boolean unusable =
                againstPinned.silence() == Silence.NO_REFERENCE || againstPinned.silence() == Silence.GRID_MISMATCH;
        if (!unusable) return new Compared(pinned, pending);
        return repin(row, pinned, closedWindow, cur, last, config, pending, windowRefs);
    }

    /**
     * Establish the closed window as the bucket's pinned reference, or leave the pin alone when the
     * window is too thin to become one.
     *
     * <p>A window too thin to be compared is too thin to become the bar everything else is compared
     * against. It rotates into prev and the bucket keeps waiting, which is the same "wait, don't skip"
     * the close criteria take.
     *
     * <p>Reached from the two places a reference stops being able to answer — nothing pinned yet, and a
     * sketch stranded on a dead grid. Both are bootstraps rather than absorbs: <em>Legitimate — absorb</em>
     * moves a LIVE reference and is reachable only from a human pressing the verb.
     */
    private Compared repin(
            MetricBaselineRow row,
            Pinned pinned,
            Window closedWindow,
            MetricSketch cur,
            Sample last,
            MetricDriftConfig config,
            @Nullable Pending pending,
            List<FindingEvidenceRepository.Ref> windowRefs) {
        if (cur.count() < config.minSample()) return new Compared(pinned, pending);
        baselines.repin(
                row.id(),
                cur.toJson(),
                closedWindow.workloadJson(),
                closedWindow.tokensJson(),
                // The rows behind the sketch, kept because they cannot be recovered later: by the time a
                // window shifts against this reference, which traffic went into it is only knowable if
                // it was written down here.
                MetricEvidenceRefs.toJson(windowRefs),
                // EVENT time of the window's last sample, not the sweep's wall clock: pinned_at describes
                // the traffic the reference summarizes, and on a backfilled corpus the two are months
                // apart. The version is that sample's, so a later deploy's re-pin is distinguishable.
                last.eventAt(),
                last.projectVersionId(),
                Instant.now().toString());
        return new Compared(
                new Pinned(cur, closedWindow.workload(), closedWindow.tokens(), windowRefs, last.projectVersionId()),
                pending);
    }

    // -----------------------------------------------------------------------------------------------
    // Emission — PROGRAM.md §6.1, one event one finding
    // -----------------------------------------------------------------------------------------------

    /**
     * Write the findings a page earned, after the §6.1 suppression rule has decided which of them are the
     * same event seen twice.
     *
     * <p><b>Tool first, then the turns nothing explained.</b> A turn that is slow because one tool is slow
     * is ONE cause: the tool row names the fix ("34 of the 38 seconds were one {@code search_docs} call"),
     * the turn row only restates the symptom, and the symptom rides on the tool row as evidence so
     * suppressing it loses nothing. An unexplained turn shift still fires on its own — that is <em>eleven
     * tool calls where three used to do</em>, where every call is as fast as it always was and only the
     * COUNT moved, which is invisible at tool grain and is the entire reason turn duration is measured.
     *
     * <p>Anything that is neither measure is written unconditionally. That set is {@code cost}, whose own
     * §6.1 rule — the four token buckets are evidence, never findings — is enforced by the measure
     * registry rather than here: a bucket that cannot open a finding has no {@code Measured} spec, so it
     * is never folded as a measure and never reaches this method. Its decomposition rides the cost
     * finding's evidence instead. Cost is deliberately NOT drawn into the suppression pairing above
     * either: a turn that got more expensive and a tool that got slower are two causes even when they arrive
     * together, and only one of them is a claim about time.
     *
     * <p><b>Suppression is a within-pass judgement</b>, over the windows that closed together. That is not
     * a shortcut but it is a limitation worth naming: turn and tool windows fill at different rates, so a
     * shift is only paired when both grains happened to rotate in the same sweep. In practice they do —
     * a page is a window's worth of turns and rather more than a window's worth of tool calls, and a
     * persisting shift re-fires on every close — and the failure mode when they do not is one extra
     * finding, not a missing one.
     */
    private int emit(String projectId, ClassifierRow signal, MetricDriftConfig config, List<Pending> pending) {
        List<Pending> tools = new ArrayList<>();
        List<Pending> turns = new ArrayList<>();
        List<Pending> others = new ArrayList<>();
        for (Pending p : pending) {
            switch (p.spec().measure()) {
                case Measure.TOOL_DURATION -> tools.add(p);
                case Measure.TURN_DURATION -> turns.add(p);
                default -> others.add(p);
            }
        }

        // Parallel to `tools`: the shift view the rule reads, and what each ended up explaining.
        List<Shift> toolShifts = new ArrayList<>(tools.size());
        List<List<Explained>> attached = new ArrayList<>(tools.size());
        for (Pending tool : tools) {
            toolShifts.add(shiftOf(tool));
            attached.add(new ArrayList<>());
        }

        List<Pending> unexplained = new ArrayList<>();
        for (Pending turn : turns) {
            MetricSuppression.Explanation best =
                    MetricSuppression.explain(shiftOf(turn), toolShifts, config.explainedByFraction());
            if (best == null) {
                unexplained.add(turn);
                continue;
            }
            attached.get(toolShifts.indexOf(best.tool())).add(explainedBy(turn, best.covered()));
            StructuredLog.info(log, Markers.OPS, "metric.finding.suppressed")
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field(
                            "suppressed",
                            MetricFindingEvidence.causeKey(
                                    turn.spec().measure(), turn.bucket().key(), turn.decision()))
                    .field("explainedBy", best.tool().bucketKey())
                    .field("covered", best.covered())
                    .log();
        }

        List<Written> written = new ArrayList<>(others.size() + tools.size() + unexplained.size());
        for (Pending other : others) {
            written.add(new Written(openFinding(projectId, other, List.of(), config), other));
        }
        for (int i = 0; i < tools.size(); i++) {
            written.add(new Written(
                    openFinding(projectId, tools.get(i), List.copyOf(attached.get(i)), config), tools.get(i)));
        }
        for (Pending turn : unexplained) {
            written.add(new Written(openFinding(projectId, turn, List.of(), config), turn));
        }
        // The pass ends at the written findings. Ruling on a shift costs an E2B microVM with a repo
        // clone, and a shift is CHANGE rather than a problem — slow is not bad and expensive is not
        // bad — so this sweep is not entitled to spend one on a lead. A human escalates from the
        // classifier's rail (`POST /findings/{id}/analysis`).
        return written.size();
    }

    /** One finding this pass wrote, beside the closed window it was written from. */
    private record Written(Recorded recorded, Pending pending) {}

    /** The rule's view of one earned finding: where it happened, and how far its median moved in ms. */
    private static Shift shiftOf(Pending pending) {
        return new Shift(
                pending.spec().measure(),
                pending.bucket().key(),
                pending.callSites().keySet(),
                pending.decision(),
                medianOf(pending.refSketch()),
                medianOf(pending.closedWindow().measure()));
    }

    /**
     * A sketch's median back in the measure's raw units, or NaN when it has none. Read through
     * {@link MetricFindingEvidence} rather than computed here so the number the rule decides on and the
     * number the evidence prints cannot drift apart.
     */
    private static double medianOf(MetricSketch sketch) {
        return MetricFindingEvidence.rawQuantile(sketch, 0.5).orElse(Double.NaN);
    }

    /** One suppressed turn shift, as the tool finding that accounts for it will carry it. */
    private static Explained explainedBy(Pending turn, double covered) {
        return new Explained(
                turn.spec().measure(),
                turn.spec().bucketKind(),
                turn.bucket().key(),
                turn.decision(),
                medianOf(turn.refSketch()),
                medianOf(turn.closedWindow().measure()),
                covered);
    }

    /**
     * The entry point a finding on this window is filed under: the one most of the window's traffic came
     * in through.
     *
     * <p>At turn grain the map has one key and this is an identity. At tool grain it is a real choice —
     * a tool bucket is not scoped by call site — and the modal caller is the honest one, because
     * {@code behavior_finding.call_site_id} is what a human clicks through to and what a Layer-2
     * triage agent clones a repo to read. First-seen order breaks a tie, so the answer is stable across a
     * replayed page rather than depending on hash order.
     */
    private static String primaryCallSite(Map<String, Long> callSites) {
        String best = BehaviorSubstrateRepository.UNATTRIBUTED;
        long bestCount = 0;
        for (Map.Entry<String, Long> e : callSites.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /**
     * Write (or bump) the finding for one shifted window.
     *
     * <p>The row is per CAUSE and the sample count accumulates onto it, so twenty consecutive shifted
     * windows leave one finding whose count climbs rather than twenty rows. The exemplar is the trace
     * that closed the window — a member of the shifted population, which is what a Layer-2 escalation
     * needs to be pointed at — and it sticks on first write while the evidence blob is refreshed every
     * window, because the blob has to describe where the bucket sits NOW for the absorb verb to absorb
     * the right thing.
     */
    private Recorded openFinding(
            String projectId, Pending pending, List<Explained> explains, MetricDriftConfig config) {
        Measured spec = pending.spec();
        Window closedWindow = pending.closedWindow();
        String causeKey =
                MetricFindingEvidence.causeKey(spec.measure(), pending.bucket().key(), pending.decision());
        String evidence = MetricFindingEvidence.toJson(
                spec.measure(),
                spec.bucketKind(),
                pending.bucket().key(),
                pending.decision(),
                pending.refSketch(),
                closedWindow.measure(),
                pending.refWorkload(),
                closedWindow.workload(),
                pending.refTokens(),
                // Null on every duration finding, and on a cost window whose traffic reported no usage at
                // all — the block is written only when there is a decomposition to write.
                closedWindow.tokens().isEmpty() ? null : closedWindow.tokens(),
                pending.sinceVersionId(),
                pending.windowOpenedAt(),
                // EVENT time again: a window closed by a backfill closed when the traffic happened, not
                // when the exporter caught up.
                pending.last().eventAt(),
                closedWindow.measure().count() >= config.windowTargetCount() ? "count" : "elapsed",
                explains,
                pending.control());
        Instant at = Instant.now();
        var recorded = findings.recordShift(
                Ids.ulid(),
                projectId,
                // Which classifier filed it, as its own column rather than a derivation. Duration and cost
                // are separately armable and separately configured, and every reader of the shared table
                // routes on classifier_key — so a single `distribution_shift` cause kind for both would
                // put the wrong detector's name on half the cases.
                classifierKeyFor(spec.measure()),
                pending.row().id(),
                causeKey,
                closedWindow.measure().count(),
                pending.sinceVersionId(),
                primaryCallSite(pending.callSites()),
                evidence,
                // The recovery horizon, and deliberately the SAME expression MetricDriftSource calls its
                // quiet window: the longest a still-regressing bucket can go between two firings. A finding
                // unrefreshed for longer had returned to its reference, so this window opens a new spell
                // and its onset moves — without which a bucket that recovered and broke again inside the
                // reopen window would never reopen its case.
                //
                // WALL clock, unlike the window bounds above, because the question is how long WE went
                // without hearing rather than how long the traffic spanned. A backfill lands months of
                // event time in one pass and would otherwise read every window as its own spell.
                at.minus(Duration.ofHours(config.windowMaxHours())).toString(),
                at.toString());
        // Both windows, enumerated, re-pointed at the window that just fired for as long as nothing has
        // ruled on this finding.
        //
        // It used to be written on the OPENING pass alone, reasoning that the claim a case is opened on
        // is the window that opened it. The payload does not agree: `recordShift` REPLACES it on every
        // close, so the finding's stated quantiles track the newest window while its evidence stayed on
        // the first. A shift that persisted for two months therefore claimed August and enumerated June,
        // and Layer 2 — which is told to audit the claim by reading the evidence — reported the gap
        // between two different windows as the detector contradicting itself, and closed a sound finding
        // on it. Whichever half moves, both must.
        //
        // `ruled()` is the whole of the freeze, and it is enough because `reopenForTriage` NULLs
        // `triage_action`: a finding sent back for a second look becomes re-pointable again, so the
        // second look audits the window it is actually about. Freezing permanently at the first ruling
        // would reintroduce the same divergence on exactly the findings stubborn enough to outlive a
        // verdict.
        if (!recorded.ruled()) {
            evidenceRefs.replace(
                    projectId,
                    recorded.findingId(),
                    FindingEvidenceRow.Role.MEMBER,
                    pending.memberRefs(),
                    at.toString());
            evidenceRefs.replace(
                    projectId,
                    recorded.findingId(),
                    FindingEvidenceRow.Role.BASELINE,
                    pending.baselineRefs(),
                    at.toString());
        }
        StructuredLog.info(log, Markers.OPS, "metric.finding.recorded")
                .field("project", projectId)
                .field("baseline", pending.row().id())
                .field("finding", recorded.findingId())
                .field("cause", causeKey)
                .field("created", recorded.created())
                .field("samples", recorded.sampleCount())
                .field("explains", explains.size())
                .log();
        return recorded;
    }

    /** One folded sample as the evidence ref it becomes: a span at span grain, its trace at turn grain. */
    private static FindingEvidenceRepository.Ref refOf(Sample sample) {
        String spanId = sample.spanId();
        return spanId == null
                ? FindingEvidenceRepository.Ref.trace(sample.traceId())
                : FindingEvidenceRepository.Ref.span(sample.traceId(), spanId);
    }

    /**
     * One comparison, on the ops log. A firing one goes on to earn a {@code behavior_finding} row (subject
     * to §6.1 suppression); the SILENT ones are logged here and nowhere else, because "did not fire" and
     * "was never asked" are indistinguishable in a findings table and only this line tells them apart.
     */
    private void report(String projectId, ClassifierRow signal, Bucket bucket, Decision decision) {
        var silence = decision.silence();
        StructuredLog.info(log, Markers.OPS, decision.fired() ? "metric.window.shift" : "metric.window.quiet")
                .field("project", projectId)
                .field("signal", signal.classifierKey())
                .field("measure", decision.measure())
                .field("bucket", bucket.key())
                .field("reference", decision.reference().wire())
                .field("w1Log", decision.w1Log())
                .field("ratio", decision.ratio())
                .field("direction", decision.direction().wire())
                .field("nRef", decision.nRef())
                .field("nCur", decision.nCur())
                .field("silence", silence == null ? null : silence.name())
                .log();
    }

    // -----------------------------------------------------------------------------------------------
    // Window mechanics
    // -----------------------------------------------------------------------------------------------

    /**
     * Whether the window that now holds {@code count} samples, opened at {@code openedAt} in event time
     * and just extended to {@code eventAt}, should close.
     *
     * <p><b>The minimum sample is a wait, not a skip</b> (PROGRAM.md §2.3). A bucket under it holds its
     * window open past the elapsed horizon rather than closing one nothing can be compared against, so a
     * tool called thirty times a week is watched on a slower clock instead of never being watched. That
     * is ADWIN's native behaviour and behaviour drift's {@code min_support} posture, and it is why
     * {@link MetricDriftConfig} clamps {@code min_sample} at or below {@code window_target_count}.
     *
     * <p>The elapsed test is on the EVENT clock at both ends. On the ingest clock a backfill would stamp
     * a whole corpus within minutes of itself, so the elapsed criterion could never fire and one window
     * would swallow the lot.
     */
    private static boolean shouldClose(long count, String openedAt, String eventAt, MetricDriftConfig config) {
        if (count < config.minSample()) return false;
        // Either criterion closes it: the count where traffic is thick, the elapsed event time where it
        // is thin. Both are read only after the minimum sample, which is what makes the floor a wait.
        return count >= config.windowTargetCount() || elapsedHours(openedAt, eventAt) >= config.windowMaxHours();
    }

    /**
     * Hours between two event stamps, or 0 when either cannot be read or they run backwards. Parsed as
     * INSTANTS, never compared as strings: {@code Instant.toString()} elides trailing zeros in the
     * fractional second, so the rendered forms are variable-length and lexical order diverges from
     * chronological ({@code ...:37Z} sorts after {@code ...:37.4Z} because {@code 'Z' > '.'}).
     *
     * <p>Backwards is not an error — event time genuinely goes backwards inside a backfilled page — it
     * simply means this sample cannot extend the window's span.
     */
    private static double elapsedHours(String openedAt, String eventAt) {
        try {
            Duration elapsed = Duration.between(Instant.parse(openedAt), Instant.parse(eventAt));
            return elapsed.isNegative() ? 0 : elapsed.toMillis() / 3_600_000.0;
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /**
     * The samples at or below this baseline's watermark are already in its sketch; everything strictly
     * after it is new. A null watermark (a bucket seen for the first time) admits everything.
     *
     * <p>On the INGEST clock, matching the SQL keyset exactly. The compare is lexicographic over the
     * rendered timestamps, which agrees with the keyset only while every row renders the same offset —
     * true of the UTC strings ingest writes, and the same caveat {@code BehaviorDriftSweep} carries.
     */
    private static List<Sample> notYetCounted(MetricBaselineRow row, List<Sample> samples) {
        String at = row.countedThroughAt();
        if (at == null) return samples;
        String id = row.countedThroughId();
        List<Sample> fresh = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            int byTime = sample.createdAt().compareTo(at);
            boolean after = byTime > 0
                    || (byTime == 0 && (id == null || sample.traceId().compareTo(id) > 0));
            if (after) fresh.add(sample);
        }
        return fresh;
    }

    /** The latest EVENT time in the page, which a backfill can put anywhere but the end. */
    private static @Nullable String maxEventAt(List<Sample> samples) {
        String best = null;
        Instant bestAt = null;
        for (Sample sample : samples) {
            try {
                Instant at = Instant.parse(sample.eventAt());
                if (bestAt == null || at.isAfter(bestAt)) {
                    bestAt = at;
                    best = sample.eventAt();
                }
            } catch (DateTimeParseException ignored) {
                // Unreadable stamps take no part rather than winning a comparison they cannot join.
            }
        }
        return best;
    }

    // -----------------------------------------------------------------------------------------------
    // Rows
    // -----------------------------------------------------------------------------------------------

    /** A bucket's row as it stands on first sight: learning, unpinned, nothing counted. */
    private static MetricBaselineRow seed(
            String projectId, String classifierId, Measured spec, Bucket bucket, String now) {
        return new MetricBaselineRow(
                Ids.ulid(),
                projectId,
                classifierId,
                spec.measure(),
                spec.bucketKind(),
                bucket.key(),
                MetricBaselineRow.State.LEARNING,
                // The pinned and current sketch, the workload, token and ref blobs beside them, then the
                // control ring and the window's open time: a bucket seen for the first time has closed
                // nothing and pinned nothing.
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
                now);
    }

    /**
     * Rehydrate a persisted sketch, or null when there is none. A blob that will not parse is logged and
     * treated as absent rather than throwing: one corrupt sketch must not dead-letter the sweep of every
     * other bucket in the project, and the next close writes a readable one over it.
     */
    private static @Nullable MetricSketch rehydrate(@Nullable String json, String baselineId) {
        if (json == null || json.isBlank()) return null;
        try {
            return MetricSketch.fromJson(json);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "metric.sketch.unreadable")
                    .field("baseline", baselineId)
                    .field("error", e.getMessage())
                    .log();
            return null;
        }
    }

    /** The same tolerance for a workload blob, and for the same reason. */
    private static @Nullable MetricWorkload rehydrateWorkload(@Nullable String json, Grid grid, String baselineId) {
        if (json == null || json.isBlank()) return null;
        try {
            return MetricWorkload.fromJson(json, MetricWorkload.grid(grid.bins()));
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "metric.workload.unreadable")
                    .field("baseline", baselineId)
                    .field("error", e.getMessage())
                    .log();
            return null;
        }
    }

    /** The same tolerance for a token blob, and for the same reason. */
    private static @Nullable MetricTokens rehydrateTokens(@Nullable String json, Grid grid, String baselineId) {
        if (json == null || json.isBlank()) return null;
        try {
            return MetricTokens.fromJson(json, MetricTokens.grid(grid.bins()));
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "metric.tokens.unreadable")
                    .field("baseline", baselineId)
                    .field("error", e.getMessage())
                    .log();
            return null;
        }
    }

    /**
     * A persisted window slot, or null when it holds no measure sketch.
     *
     * <p>A slot with a sketch but no sidecar is the ordinary shape twice over: for rows written before
     * those columns existed, and for every duration baseline, which never writes a token blob at all. It
     * rehydrates with an EMPTY sidecar rather than being discarded — the measure half of that window is
     * still a perfectly good reference, and the evidence blob renders the missing pairs as nulls, which
     * reads as "not recorded then" rather than as a workload or a token count of zero.
     */
    private static @Nullable Window rehydrateWindow(
            @Nullable String sketchJson,
            @Nullable String workloadJson,
            @Nullable String tokensJson,
            Grid grid,
            String baselineId) {
        MetricSketch sketch = rehydrate(sketchJson, baselineId);
        if (sketch == null) return null;
        MetricWorkload workload = rehydrateWorkload(workloadJson, grid, baselineId);
        MetricTokens tokens = rehydrateTokens(tokensJson, grid, baselineId);
        return new Window(
                sketch,
                workload == null ? new MetricWorkload(MetricWorkload.grid(grid.bins())) : workload,
                tokens == null ? new MetricTokens(MetricTokens.grid(grid.bins())) : tokens);
    }
}
