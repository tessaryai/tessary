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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The WINDOW-grain sweep: an ordinary {@code classifier} job whose keyset cursor walks {@code trace} rows. Windows are
 * cut on event time, while each baseline's watermark stays on ingest time so a re-swept page never re-folds samples.
 * {@link #sweep} is one transaction because each JdbcClient write autocommits and a gap between them double-counts.
 */
@Component
public class MetricDriftSweep implements ClassifierSweep {

    private static final Logger log = LoggerFactory.getLogger(MetricDriftSweep.class);

    /**
     * Traces read per pass: a transport bound, not a detector dial. Sized at the default {@code window_target_count} so
     * a thick bucket closes about one window per pass.
     */
    static final int PAGE_SIZE = 500;

    /**
     * Named rather than derived: {@code classifier_key} is what every shared reader routes on and must match the
     * backfilled rows.
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
     * {@code windowsClosed} is progress only this sweep has; {@link #sweep(SweepContext)} narrows to the shared
     * {@link SweepOutcome}.
     */
    public record MetricSweepOutcome(int scanned, int fired, int windowsClosed) {

        static final MetricSweepOutcome EMPTY = new MetricSweepOutcome(0, 0, 0);

        SweepOutcome narrowed() {
            return new SweepOutcome(scanned, fired);
        }
    }

    /**
     * Declared explicitly so a classifier with no dispatch of its own fails loudly instead of silently running metric
     * drift.
     */
    @Override
    public Set<String> kinds() {
        return Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);
    }

    /**
     * {@code @Transactional} is on both this method and {@link #sweepMetrics}: the self-invocation bypasses Spring's
     * proxy, and the integration suite calls {@code sweepMetrics} directly.
     */
    @Transactional
    @Override
    public SweepOutcome sweep(SweepContext ctx) {
        return sweepMetrics(ctx.job(), ctx.classifier()).narrowed();
    }

    /**
     * One transaction ending at {@code markSwept}: baselines, findings and the cursor commit together, so a retry never
     * re-folds a half-recorded page.
     */
    @Transactional
    public MetricSweepOutcome sweepMetrics(ClassifierJobRow job, ClassifierRow signal) {
        MetricDriftConfig config = MetricDriftConfig.of(mapper, signal.configJson());
        List<Measured> live = config.measured();
        if (live.isEmpty()) {
            // Finish rather than fail: the cursor must not advance over traffic nothing measured, and a config edit
            // fixes it.
            StructuredLog.info(log, Markers.OPS, "metric.sweep.no-measures")
                    .field("project", job.projectId())
                    .field("signal", signal.classifierKey())
                    .log();
            jobs.markSwept(job.id(), null, null);
            return MetricSweepOutcome.EMPTY;
        }

        // The same (trace.started_at, trace.id) keyset the span- and trace-grain sweeps use, so this job row needs no
        // new columns.
        List<TraceHead> heads = substrate.tracesAfter(job.projectId(), job.cursorAt(), job.cursorId(), PAGE_SIZE);
        if (heads.isEmpty()) {
            jobs.markSwept(job.id(), null, null);
            return MetricSweepOutcome.EMPTY;
        }

        String now = Instant.now().toString();
        MetricSource.Tally tally = new MetricSource.Tally();
        Folded folded = Folded.NONE;

        // Read once per page: the confirmed set is a handful and is consulted in memory for thousands of buckets.
        Map<String, List<ConfirmedSpan>> confirmed =
                findings.confirmedSpansBySubject(job.projectId(), METRIC_CLASSIFIERS);

        List<Measured> turnMeasures = config.measuredAt(Grain.TURN);
        int held = 0;
        if (!turnMeasures.isEmpty()) {
            List<TurnMetrics> turns = source.turnMetrics(job.projectId(), heads, tally);
            int admissible = admissibleThrough(heads, turns, config);
            held = heads.size() - admissible;
            if (admissible == 0) {
                // Finish WITHOUT moving the cursor, so the next tick re-offers the traces still arriving.
                jobs.markSwept(job.id(), null, null);
                return MetricSweepOutcome.EMPTY;
            }
            if (held > 0) {
                heads = heads.subList(0, admissible);
                turns = turns.subList(0, admissible);
            }
            for (Measured spec : turnMeasures) {
                folded = folded.plus(foldMeasure(
                        job.projectId(),
                        signal,
                        config,
                        spec,
                        inCallSiteScope(signal, turnSamples(heads, turns, spec)),
                        confirmed,
                        now));
            }
        }

        List<Measured> spanMeasures = config.measuredAt(Grain.OBSERVATION);
        if (!spanMeasures.isEmpty()) {
            // Exact only while a span carries ONE reading: tool_duration is the only measure at this grain.
            Map<Bucket, List<Sample>> byTool =
                    inCallSiteScope(signal, toolSamples(heads, source.toolMetrics(job.projectId(), heads, tally)));
            for (Measured spec : spanMeasures) {
                folded = folded.plus(foldMeasure(job.projectId(), signal, config, spec, byTool, confirmed, now));
            }
        }

        // Both grains are folded before ANY finding is written: the §6.1 suppression rule needs to see both.
        int fired = emit(job.projectId(), signal, config, folded.pending());

        // Advance over the WHOLE page, including unreadable heads, or they would be re-offered on every pass forever.
        TraceHead last = heads.get(heads.size() - 1);
        jobs.markSwept(job.id(), last.eventAt(), last.traceId());

        // Logged whether or not anything closed: a measure abstaining on all traffic looks like a quiet week in the
        // findings.
        StructuredLog.info(log, Markers.OPS, "metric.sweep.windows")
                .field("project", job.projectId())
                .field("signal", signal.classifierKey())
                .field("scanned", heads.size())
                // Steadily equal to PAGE_SIZE means root spans are not arriving at all.
                .field("heldForRoot", held)
                .field("windowsClosed", folded.closed())
                .field("findings", fired)
                .field("measures", tally.summary())
                .log();
        return new MetricSweepOutcome(heads.size(), fired, folded.closed());
    }

    /** Written only after both grains are folded, because the §6.1 suppression rule needs to see both. */
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
     * {@code baselineRefs} is empty for the rolling-control arm, which has no set of rows behind it.
     */
    private record Pending(
            Measured spec,
            Bucket bucket,
            MetricBaselineRow row,
            Decision decision,
            MetricReading refSketch,
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

    /**
     * How many leading heads may be folded now: up to the first trace whose root span has not arrived and is younger
     * than {@link MetricDriftConfig#settleSeconds}. Sweeping a rootless trace would drop it for good, and the dropout
     * is length-biased: a regression that lengthens turns would make the detector quieter.
     */
    private static int admissibleThrough(List<TraceHead> heads, List<TurnMetrics> turns, MetricDriftConfig config) {
        Instant now = Instant.now();
        long backstop = Math.max(0, config.settleSeconds());
        int n = Math.min(heads.size(), turns.size());
        for (int i = 0; i < n; i++) {
            if (turns.get(i).completion() != MetricSource.Completion.NO_ROOT_SPAN) continue;
            if (stillArriving(heads.get(i).eventAt(), now, backstop)) return i;
        }
        return n < heads.size() ? n : heads.size();
    }

    private static boolean stillArriving(String eventAt, Instant now, long backstopSeconds) {
        Duration age = Duration.between(Instant.parse(eventAt), now);
        return !age.isNegative() && age.getSeconds() < backstopSeconds;
    }

    /**
     * One reading with both clocks. {@code createdAt} and {@code traceId} are the ingest keyset the watermark compares
     * against; {@code eventAt} is what windows are cut on; {@code logValue} is {@code ln} of the measure so
     * {@code e^W₁} is a multiplicative shift. {@code tokens} is folded only under {@code cost}.
     */
    private record Sample(
            String traceId,
            /** The span this reading came off at span grain, null at turn grain. Never read by the statistic. */
            @Nullable String spanId,
            String createdAt,
            String eventAt,
            @Nullable String projectVersionId,
            String callSiteId,
            double logValue,
            MetricSource.Workload workload,
            @Nullable TokenReadings tokens) {}

    /**
     * Exactly the columns {@code metric_baseline_scope} is unique on. The call site is not here: two tool buckets
     * differing only in call site would resolve to the same row and count their samples twice.
     */
    private record Bucket(String key) {}

    /**
     * Zipped with {@code turnMetrics} by index. {@code __unattributed__} is not a bucket: its distribution moves
     * whenever the mix does, so every finding on it would be an artefact.
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
                            null,
                            head.eventAt(),
                            head.eventAt(),
                            head.projectVersionId(),
                            turn.callSiteId(),
                            value.logValue(),
                            turn.workload(),
                            Measure.COST.equals(spec.measure()) ? turn.tokenReadings() : null));
        }
        return byBucket;
    }

    /**
     * Walked in head order, not query order: the watermark is a keyset on {@code (trace.created_at, trace.id)} and a
     * later fold would otherwise exclude an earlier trace's spans forever. No workload is folded, since folding the
     * enclosing turn's would condition on the answer.
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
                                // The TRACE's ingest keyset, not the span's: every span of a trace shares one watermark
                                // position, which keeps a replayed page idempotent.
                                head.traceId(),
                                tool.observationId(),
                                head.eventAt(),
                                // EVENT time is the span's own start: a tool called an hour into a long trace belongs
                                // in that hour's window.
                                tool.eventAt(),
                                head.projectVersionId(),
                                tool.callSiteId(),
                                value.logValue(),
                                MetricSource.Workload.NONE,
                                null));
            }
        }
        return byBucket;
    }

    /** Drop samples whose entry point is outside the classifier's call sites, and any bucket left empty. */
    private static Map<Bucket, List<Sample>> inCallSiteScope(ClassifierRow signal, Map<Bucket, List<Sample>> byBucket) {
        if (signal.callSiteIds() == null) return byBucket;
        Map<Bucket, List<Sample>> kept = new LinkedHashMap<>();
        for (Map.Entry<Bucket, List<Sample>> e : byBucket.entrySet()) {
            List<Sample> inScope = e.getValue().stream()
                    .filter(sample -> signal.runsOn(sample.callSiteId()))
                    .toList();
            if (!inScope.isEmpty()) kept.put(e.getKey(), inScope);
        }
        return kept;
    }

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
     * One window slot's three summaries over ONE set of samples, carried together so they can never come from different
     * turns. Empty sidecars serialize to null.
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

        @Nullable
        String workloadJson() {
            return workload.isEmpty() ? null : workload.toJson();
        }

        @Nullable
        String tokensJson() {
            return tokens.isEmpty() ? null : tokens.toJson();
        }
    }

    /**
     * Threaded through the close loop rather than re-read: the first close of a fresh bucket establishes the pin the
     * second is compared against. {@code versionId} is what a finding against it reports as {@code since_version_id}.
     */
    private record Pinned(
            @Nullable MetricSketch sketch,
            @Nullable MetricWorkload workload,
            @Nullable MetricTokens tokens,
            List<FindingEvidenceRepository.Ref> refs,
            @Nullable String versionId) {}

    /**
     * Fold one bucket's slice of the page, closing and comparing whenever the window's criteria are met, possibly more
     * than once.
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
        MetricControl control = MetricControl.fromJson(row.controlJson());
        // Held for the whole page, so every window it closes is judged against a control excluding the same days.
        Set<String> excludedDays = MetricBaselineReference.excludedDays(confirmed.get(row.id()));
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
        boolean countZeroed = false;
        if (persisted != null && persisted.measure().gridId().equals(gridId)) {
            current = persisted;
            count = row.currentCount();
            openedAt = row.currentOpenedAt();
        } else {
            // Nothing written yet, or hist_bins changed under a live project: the count, open time and sketch restart
            // together. The control ring is left alone; its dead-grid days are skipped by MetricControl#resolve and age
            // out.
            current = new Window(grid);
            count = 0;
            openedAt = null;
            if (persisted != null) {
                baselines.closeWindow(row.id(), row.controlJson(), now);
                countZeroed = true;
            }
        }

        // The event time of the window's FIRST sample.
        String openTimeForWrite = null;
        int closed = 0;
        List<Pending> pending = new ArrayList<>();
        Map<String, Long> callSites = new LinkedHashMap<>();
        // Rehydrated from the row, not started empty: a window spans pages, and member evidence must cover the whole
        // population, not the page its close landed in.
        List<FindingEvidenceRepository.Ref> windowRefs =
                new ArrayList<>(MetricEvidenceRefs.fromJson(row.currentRefsJson()));
        if (countZeroed) {
            // The stale-grid branch discarded the window, so its refs go with it.
            windowRefs.clear();
        }
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
            // The window's own EVENT day, never the sweep's wall clock, so a replayed backfill judges each window
            // against its own 21 event-days.
            String eventDay = MetricControl.dayOf(Instant.parse(sample.eventAt()));
            // Compared BEFORE the fold: folding first would compare the window against a reference that already
            // contains it.
            MetricControl.Resolved reference = control.resolve(grid, eventDay, excludedDays);
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
                    grid, eventDay, closedWindow.measure(), closedWindow.workload(), closedWindow.tokens());
            baselines.closeWindow(row.id(), control.toJson(), now);
            pinned = compared.pinned();
            if (compared.pending() != null) pending.add(compared.pending());
            callSites = new LinkedHashMap<>();
            windowRefs = new ArrayList<>();
            if (!MetricBaselineRow.State.ARMED.equals(state)
                    && closedWindow.measure().count() >= config.minSample()) {
                // Arming stays out of advanceWindow: counting traffic must not arm a bucket as a side effect.
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

        boolean empty = current.measure().count() == 0;
        baselines.updateCurrentSketch(
                row.id(),
                empty ? null : current.measure().toJson(),
                empty ? null : current.workloadJson(),
                empty ? null : current.tokensJson(),
                // `empty` is the window, not the list: refs must be dropped with an empty window rather than outliving
                // the sketch.
                empty ? null : MetricEvidenceRefs.toJson(windowRefs),
                now);
        Sample last = fresh.get(fresh.size() - 1);
        baselines.advanceWindow(
                row.id(),
                countZeroed ? count : fresh.size(),
                now,
                openTimeForWrite,
                maxEventAt(fresh),
                // INGEST clock: the watermark shares the counters' lifetime, so a re-swept page cannot fold the same
                // trace twice.
                last.createdAt(),
                last.traceId());
        return new Folded(closed, List.copyOf(pending));
    }

    private record Compared(Pinned pinned, @Nullable Pending pending) {}

    /**
     * Run both comparisons for a just-closed window and earn at most one finding, preferring the pinned one a human can
     * absorb. Pinning here only bootstraps an empty or dead-grid reference; moving a live one is the human absorb verb,
     * never automatic.
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
                    // The rolling control has no window of rows behind it, so this arm carries a member set and no
                    // baseline set.
                    List.of(),
                    control);
        }

        boolean unusable =
                againstPinned.silence() == Silence.NO_REFERENCE || againstPinned.silence() == Silence.GRID_MISMATCH;
        if (!unusable) return new Compared(pinned, pending);
        return repin(row, pinned, closedWindow, cur, last, config, pending, windowRefs);
    }

    /**
     * Establish the closed window as the pinned reference, unless it is too thin to be compared. Only a bootstrap,
     * never an absorb.
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
                // Kept because which traffic went into the reference cannot be recovered later.
                MetricEvidenceRefs.toJson(windowRefs),
                // EVENT time of the window's last sample: on a backfilled corpus the sweep's wall clock is months away.
                last.eventAt(),
                last.projectVersionId(),
                Instant.now().toString());
        return new Compared(
                new Pinned(cur, closedWindow.workload(), closedWindow.tokens(), windowRefs, last.projectVersionId()),
                pending);
    }

    /**
     * Write the findings a page earned. Tool shifts first, then turn shifts no tool shift explains, which ride on the
     * tool row as evidence. Cost is written unconditionally and kept out of the pairing. Pairing only sees windows that
     * closed in the same pass, so a miss costs one extra finding, not a missing one.
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
        // Ruling on a shift costs an E2B microVM and a shift is change, not a problem, so a human escalates via `POST
        // /findings/{id}/analysis`.
        return written.size();
    }

    private record Written(Recorded recorded, Pending pending) {}

    private static Shift shiftOf(Pending pending) {
        return new Shift(
                pending.bucket().key(),
                pending.callSites().keySet(),
                pending.decision(),
                medianOf(pending.refSketch()),
                medianOf(pending.closedWindow().measure()));
    }

    /**
     * Read through {@link MetricFindingEvidence} so the number the rule decides on and the number the evidence prints
     * cannot drift apart.
     */
    private static double medianOf(MetricReading sketch) {
        return MetricFindingEvidence.rawQuantile(sketch, 0.5).orElse(Double.NaN);
    }

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
     * The call site most of the window's traffic came through. First-seen order breaks a tie, so a replayed page gives
     * a stable answer.
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
     * Write or bump the per-cause finding for one shifted window. The exemplar sticks on first write; the evidence blob
     * is refreshed every window so absorb absorbs where the bucket sits now.
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
                closedWindow.tokens().isEmpty() ? null : closedWindow.tokens(),
                pending.sinceVersionId(),
                pending.windowOpenedAt(),
                // EVENT time: a backfilled window closed when the traffic happened.
                pending.last().eventAt(),
                closedWindow.measure().count() >= config.windowTargetCount() ? "count" : "elapsed",
                explains,
                pending.control());
        Instant at = Instant.now();
        // EVENT time of the window's close, so a backfill reads each window's spell as it read at the time.
        Instant eventAt = Instant.parse(pending.last().eventAt());
        var recorded = findings.recordShift(
                Ids.ulid(),
                projectId,
                classifierKeyFor(spec.measure()),
                pending.row().id(),
                causeKey,
                closedWindow.measure().count(),
                pending.sinceVersionId(),
                primaryCallSite(pending.callSites()),
                evidence,
                pending.last().eventAt(),
                // Deliberately the SAME expression MetricDriftSource uses for its quiet window, so a bucket that
                // recovered and broke again reopens its case.
                eventAt.minus(Duration.ofHours(config.windowMaxHours())).toString(),
                at.toString());
        // Both evidence windows move to the window that just fired: `recordShift` replaces the stated quantiles, and
        // stale evidence would contradict them. `recordShift` already excludes a ruled row.
        evidenceRefs.replace(
                projectId, recorded.findingId(), FindingEvidenceRow.Role.MEMBER, pending.memberRefs(), at.toString());
        evidenceRefs.replace(
                projectId,
                recorded.findingId(),
                FindingEvidenceRow.Role.BASELINE,
                pending.baselineRefs(),
                at.toString());
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

    private static FindingEvidenceRepository.Ref refOf(Sample sample) {
        String spanId = sample.spanId();
        return spanId == null
                ? FindingEvidenceRepository.Ref.trace(sample.traceId())
                : FindingEvidenceRepository.Ref.span(sample.traceId(), spanId);
    }

    /**
     * One comparison on the ops log. Silent comparisons are logged only here: "did not fire" and "was never asked" are
     * otherwise indistinguishable.
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

    /**
     * The minimum sample is a wait, not a skip: a thin bucket holds its window open past the elapsed horizon. Elapsed
     * time is on the EVENT clock, or a backfill would put a whole corpus in one window.
     */
    private static boolean shouldClose(long count, String openedAt, String eventAt, MetricDriftConfig config) {
        if (count < config.minSample()) return false;
        return count >= config.windowTargetCount() || elapsedHours(openedAt, eventAt) >= config.windowMaxHours();
    }

    /**
     * Parsed as instants, never compared as strings: {@code Instant.toString()} is variable-length, so lexical order
     * diverges from chronological. Event time can run backwards inside a backfilled page; that yields 0.
     */
    private static double elapsedHours(String openedAt, String eventAt) {
        Duration elapsed = Duration.between(Instant.parse(openedAt), Instant.parse(eventAt));
        return elapsed.isNegative() ? 0 : elapsed.toMillis() / 3_600_000.0;
    }

    /**
     * Samples strictly after the baseline's watermark; a null watermark admits everything. The lexicographic compare
     * matches the SQL keyset only while every timestamp renders in UTC.
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

    private static @Nullable String maxEventAt(List<Sample> samples) {
        String best = null;
        Instant bestAt = null;
        for (Sample sample : samples) {
            Instant at = Instant.parse(sample.eventAt());
            if (bestAt == null || at.isAfter(bestAt)) {
                bestAt = at;
                best = sample.eventAt();
            }
        }
        return best;
    }

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
     * A blob that will not parse is logged and treated as absent: one corrupt sketch must not dead-letter every other
     * bucket.
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
     * A slot with a sketch but no sidecar (older rows, every duration baseline) rehydrates with an EMPTY sidecar; the
     * measure half is still a good reference.
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
