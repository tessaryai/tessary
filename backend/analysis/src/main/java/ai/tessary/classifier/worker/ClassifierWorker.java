// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.catalog.PagedDetector;
import ai.tessary.classifier.catalog.PagedDetector.PageAction;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.EncoderUnreachableException;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.gate.PreDeployCheckService;
import ai.tessary.gate.PreDeployCheckService.ClassifierDiscovery;
import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.RepeatedFailureLogger;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The async signal-detection worker. On an operational heartbeat it dead-letters poison jobs,
 * enqueues a sweep job per enabled signal for every project that has substrate observations, then
 * claims due jobs ({@code FOR UPDATE SKIP LOCKED}) and dispatches each to the bounded {@code
 * signalTaskExecutor}. Each sweep reads observations past the classifier's cursor, runs the
 * built-in detector, writes signal detections as verdicts idempotently, and advances the cursor.
 *
 * <p>This worker doesn't provision the catalog: which built-ins a project has is {@code
 * ClassifierCatalogWorker}'s job, over every active project. Which of them this worker sweeps is
 * scoped to "projects with observations," since a job on a project with no spans would be claimed
 * and dispatched to sweep an empty window every tick forever.
 *
 * <p>Strictly off the ingest hot path: it reads what the substrate write path produced and is
 * never hooked into {@code SubstrateWriter.enqueue}. The worker itself is always on, and every
 * project is seeded, so participation is decided only by which built-ins the tenant left enabled.
 */
@Component
public class ClassifierWorker {

    private static final Logger log = LoggerFactory.getLogger(ClassifierWorker.class);

    private static final int MAX_CLAIM_ROUNDS = 10_000;
    // Jobs claimed per round; the bounded signalTaskExecutor backpressures dispatch, so this only
    // bounds how many job rows one UPDATE...SKIP LOCKED flips at once (not the per-sweep batch size).
    private static final int CLAIM_BATCH = 5;

    /**
     * The kinds whose sweep keeps paging inside one claim until it reaches the head of the stream.
     *
     * <p>A backlog for these arrives all at once rather than a page a minute: a backfill upload lands months
     * of spans in one go, and Malformed Output rewinds to the start of history the moment a call site's schema
     * arrives. At one page a tick, a 250,000-span project takes most of a day to catch up. Both are
     * deterministic and cheap per span.
     *
     * <p>The encoder-backed kind (groundedness) drains too, but at {@code encoder-batch-size} a page rather than
     * {@code batch-size}: each observation is a model call, so a page is sized to finish well inside the
     * lease on a CPU encoder, the cursor lands after every page, and the scorer holds at most {@code
     * tessary.observer.encoder.max-inflight} requests open and backs off on a 429 — so a backlog reaches
     * the encoder at the pace it can take, never as one tick's worth of calls.
     */
    private static final Set<String> DRAIN_TO_HEAD = Set.of(
            BuiltInDetector.Kind.SECRET_LEAK, BuiltInDetector.Kind.MALFORMED_OUTPUT, BuiltInDetector.Kind.GROUNDEDNESS);

    // A job stuck failing every tick gets one full stacktrace, then a "still failing" summary
    // every 30 occurrences (~30 ticks at the default 60s heartbeat) instead of one per tick.
    private static final int SWEEP_FAILURE_SUMMARY_EVERY = 30;

    private final ClassifierService signalService;
    private final ClassifierRepository signals;
    private final ClassifierJobRepository jobs;
    private final ClassifierDetectionWriteRepository detections;
    private final ClassifierArming arming;
    private final SubstrateReadRepository substrate;
    private final BuiltInClassifierCatalog catalog;
    private final PreDeployCheckService preDeployChecks;
    /**
     * Every classifier sweep on this classpath, by the detector kind it claims. The worker doesn't
     * know which classifiers exist; a classifier attaches by being on the classpath.
     */
    private final ClassifierSweepRegistry sweeps;

    /** Population work a classifier does once its sweep has caught up; see {@link ClassifierCatchUp}. */
    private final ObjectProvider<ClassifierCatchUp> catchUps;

    private final ClassifierProperties props;
    private final TaskExecutor executor;
    private final TraceMdcBridge traceBridge;
    private final String leaseOwner =
            shortHost() + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final RepeatedFailureLogger sweepFailures = new RepeatedFailureLogger(SWEEP_FAILURE_SUMMARY_EVERY);

    public ClassifierWorker(
            ClassifierService signalService,
            ClassifierRepository signals,
            ClassifierJobRepository jobs,
            ClassifierDetectionWriteRepository detections,
            ClassifierArming arming,
            SubstrateReadRepository substrate,
            BuiltInClassifierCatalog catalog,
            PreDeployCheckService preDeployChecks,
            ClassifierSweepRegistry sweeps,
            ObjectProvider<ClassifierCatchUp> catchUps,
            ClassifierProperties props,
            TraceMdcBridge traceBridge,
            @Qualifier("signalTaskExecutor") TaskExecutor executor) {
        this.signalService = signalService;
        this.signals = signals;
        this.jobs = jobs;
        this.detections = detections;
        this.arming = arming;
        this.substrate = substrate;
        this.catalog = catalog;
        this.preDeployChecks = preDeployChecks;
        this.sweeps = sweeps;
        this.catchUps = catchUps;
        this.props = props;
        this.traceBridge = traceBridge;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${tessary.classifier.heartbeat-ms:60000}")
    public void tick() {
        // Spring's built-in @Scheduled observability already opens a span for this invocation
        // (visible in Tempo as "task signalWorker.tick"), but that span never reaches MDC on the
        // scheduler thread by itself. Bind it now, before any job is dispatched, so sweep() logs
        // (via MdcTaskDecorator on signalTaskExecutor) carry the same trace/span id and pivot to
        // this tick's trace in Grafana.
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        try {
            int exhausted = jobs.failExhausted(props.getMaxAttempts());
            if (exhausted > 0) {
                StructuredLog.error(log, Markers.OPS, "signal.dead-lettered")
                        .field("count", exhausted)
                        .log();
            }
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "signal.dead-letter-sweep-failed")
                    .field("error", e.getMessage())
                    .log();
        }

        List<String> projects;
        try {
            projects = substrate.projectsWithObservations();
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "signal.project-scan-failed")
                    .field("error", e.getMessage())
                    .log();
            return;
        }
        for (String projectId : projects) {
            try {
                signalService.enqueueEnabled(projectId);
            } catch (RuntimeException e) {
                StructuredLog.warn(log, Markers.OPS, "signal.enqueue-failed")
                        .field("project", projectId)
                        .field("error", e.getMessage())
                        .log();
            }
        }

        int dispatched = 0;
        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            List<ClassifierJobRow> batch;
            try {
                batch = jobs.claimBatch(leaseOwner, CLAIM_BATCH, props.getLeaseSeconds(), props.getMaxAttempts());
            } catch (RuntimeException e) {
                StructuredLog.warn(log, Markers.OPS, "signal.claim-failed")
                        .field("error", e.getMessage())
                        .log();
                break;
            }
            if (batch.isEmpty()) break;
            for (ClassifierJobRow job : batch) {
                executor.execute(() -> sweep(job));
                dispatched++;
            }
        }
        if (dispatched > 0) {
            StructuredLog.info(log, Markers.OPS, "signal.tick.dispatched")
                    .message("dispatched %d signal sweep(s)", dispatched)
                    .field("dispatched", dispatched)
                    .log();
        }
    }

    /** Whether {@code e}, or anything it wraps, is the scorer finding the model unreachable. */
    private static boolean isEncoderUnreachable(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof EncoderUnreachableException) return true;
        }
        return false;
    }

    /** Test seam: the lease owner this worker claims as, for tests that claim a job on its behalf. */
    String leaseOwnerForTest() {
        return leaseOwner;
    }

    /** Test seam: run the production {@link #sweep} for one job directly (mirrors MeteringWorker's analogous seam). */
    void sweepForTest(ClassifierJobRow job) {
        sweep(job);
    }

    /**
     * Run one classifier's sweep: load its (enabled) definition, dispatch its detector over observations
     * past the cursor, write fired detections idempotently, advance the cursor to the last observation
     * seen. A kind with no detection table completes without scoring (so it doesn't re-scan forever).
     */
    private void sweep(ClassifierJobRow job) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put(LogContext.PROJECT_ID, job.projectId());
        // Structured (not message-text-only) so Loki can filter on {jobId="..."} instead of
        // substring-matching "job=" out of the body (obs Gap 4).
        ctx.put(LogContext.JOB_ID, job.id());
        Instant start = Instant.now();
        // Readable signal key when the row loads; failure paths use it for `.field("signal", …)`
        // and keep the ULID under `classifierId` so Loki filters stay consistent with post-load events.
        @Nullable String classifierKey = null;
        try (LogContext ignored = LogContext.put(ctx)) {
            Optional<ClassifierRow> maybe = signals.findById(job.projectId(), job.classifierId());
            if (maybe.isEmpty() || !maybe.get().enabled()) {
                String reason = maybe.isEmpty() ? "missing" : "disabled";
                StructuredLog.info(log, Markers.OPS, "signal.sweep.inert")
                        .field("job", job.id())
                        .field("signal", maybe.map(ClassifierRow::classifierKey).orElse(null))
                        .field("classifierId", job.classifierId())
                        .field("reason", reason)
                        .log();
                jobs.markSwept(job.id(), null, null); // signal removed/disabled: leave the cursor, finish
                sweepFailures.clear(job.id());
                return;
            }
            ClassifierRow signal = maybe.get();
            classifierKey = signal.classifierKey();
            if (signalService.encoderDown(signal)) {
                // Pending from before the model went away: hand it back unrun and uncounted. The
                // enqueue gate keeps it from coming back until the model answers again.
                jobs.releaseWithoutAttempt(job.id(), leaseOwner);
                sweepFailures.clear(job.id());
                StructuredLog.debug(log, "groundedness.sweep.skipped")
                        .message("released %s unrun: its model is down", signal.classifierKey())
                        .field("job", job.id())
                        .field("signal", signal.classifierKey())
                        .field("classifierId", signal.id())
                        .field("reason", "model down")
                        .log();
                return;
            }
            try (LogContext ignoredSignal = LogContext.with(LogContext.CLASSIFIER_KEY, signal.classifierKey())) {
                // DEBUG, not INFO: this announces intent, not state. It was 45% of production log
                // volume (168 lines / 10 min) and every fact in it also appears on the completion
                // line below, which additionally says what actually happened.
                StructuredLog.debug(log, "signal.sweep.start")
                        .field("job", job.id())
                        .field("signal", signal.classifierKey())
                        .field("classifierId", signal.id())
                        .field("project", job.projectId())
                        .field("cursor", job.cursorId())
                        .field("cursorAt", job.cursorAt())
                        .log();
                sweepSignal(job, signal, start);
            }
        } catch (RuntimeException e) {
            if (isEncoderUnreachable(e)) {
                // The model is asleep or stopped, not broken: the scorer has already marked it down,
                // so hand the job back without spending an attempt. Five of these in a row must never
                // dead-letter the sweep; a 5xx or a 401 still does.
                jobs.releaseWithoutAttempt(job.id(), leaseOwner);
                sweepFailures.clear(job.id());
                StructuredLog.info(log, Markers.OPS, "signal.sweep.encoder-unreachable")
                        .message(
                                "%s paused: its model is not answering",
                                classifierKey != null ? classifierKey : job.classifierId())
                        .field("job", job.id())
                        .field("signal", classifierKey)
                        .field("classifierId", job.classifierId())
                        .durationMs(start)
                        .log();
                return;
            }
            // One sweep failing is a WARN, deduped: the first of a streak carries the stacktrace,
            // repeats collapse to a summary. The alertable ERROR is reserved for the budget-exhausted
            // dead-letter transition. Clearing the streak on dead-letter means each post-cooldown
            // revival probe that fails again logs fresh.
            boolean deadLettered = jobs.markFailed(job.id(), e.getMessage(), props.getMaxAttempts());
            final @Nullable String readableSignal = classifierKey;
            if (deadLettered) {
                sweepFailures.clear(job.id());
                StructuredLog.error(log, Markers.OPS, "signal.sweep.dead-lettered")
                        .field("job", job.id())
                        .field("signal", readableSignal)
                        .field("classifierId", job.classifierId())
                        .field("attempts", props.getMaxAttempts())
                        .cause(e)
                        .log();
            } else {
                sweepFailures.record(
                        job.id(),
                        () -> StructuredLog.warn(log, Markers.OPS, "signal.sweep.failed")
                                .field("job", job.id())
                                .field("signal", readableSignal)
                                .field("classifierId", job.classifierId())
                                .field("attempts", job.attempts())
                                .cause(e)
                                .log(),
                        count -> StructuredLog.warn(log, Markers.OPS, "signal.sweep.still-failing")
                                .field("job", job.id())
                                .field("signal", readableSignal)
                                .field("classifierId", job.classifierId())
                                .field("occurrences", count)
                                .log());
            }
        }
    }

    /**
     * The body of one sweep, once the signal is resolved and its {@code classifierKey} is bound to
     * MDC. The grain enum is the single source of truth for which seam runs: the fitting tier
     * (WINDOW) scores a unit larger than an observation against fitted per-project state,
     * so it can't ride the observation-grain detector seam, but it reuses this job's cursor, lease,
     * and dead-letter budget verbatim. There's no new scheduler and no new job table for any of it.
     *
     * <p>Which sweep runs inside that seam is a lookup, not an {@code if/else} chain: it holds
     * {@link ClassifierSweepRegistry} and a classifier attaches by being on the classpath.
     */
    private void sweepSignal(ClassifierJobRow job, ClassifierRow signal, Instant start) {
        Grain grain = catalog.grainFor(signal.detector());
        switch (grain) {
            case WINDOW -> {
                Optional<ClassifierSweep> sweep = sweeps.forKind(signal.detector());
                if (sweep.isEmpty()) {
                    // Inert, deliberately: not a throw and not a dead-letter, since this job is
                    // re-pended by every heartbeat and a failing branch here would burn the attempt
                    // budget of a project whose only fault is having no sweep registered for this kind,
                    // forever. Not a fallthrough either: routing an unmatched WINDOW kind to metric
                    // drift once kept a second copy of every duration and cost baseline and emitted a
                    // duplicate finding under the wrong classifier id, with nothing failing loudly. And
                    // the classifier is not retired for it; see ClassifierService#retireDroppedBuiltIns,
                    // where leaving the catalog is permanent.
                    StructuredLog.warn(log, Markers.OPS, "signal.sweep.no-handler")
                            .message(
                                    "no classifier sweep is registered for detector kind %s; completing the job "
                                            + "without analysis",
                                    signal.detector())
                            .field("job", job.id())
                            .field("signal", signal.classifierKey())
                            .field("classifierId", signal.id())
                            .field("project", job.projectId())
                            .field("detector", signal.detector())
                            .field("grain", grain.name().toLowerCase(Locale.ROOT))
                            .field("registered", String.join(",", sweeps.registeredKinds()))
                            .log();
                    jobs.markSwept(job.id(), null, null);
                    sweepFailures.clear(job.id());
                    return;
                }
                SweepOutcome outcome = sweep.get().sweep(new SweepContext(job, signal));
                sweepFailures.clear(job.id());
                logSweepComplete(job, signal, grain, outcome.scanned(), outcome.fired(), 1, start);
            }
            case TURN, OBSERVATION -> sweepObservationGrain(job, signal, grain, start);
        }
    }

    /**
     * Drop turns whose conversation this classifier has already flagged: a conversation with any detection
     * nobody has cleared is not scored again, whatever its band, and one whose flag was cleared is.
     *
     * <p>A turn-grain classifier's subject is what the user said, but the thing an operator acts on is
     * the conversation, which is one event, not one per turn.
     */
    private List<SubstrateObservation> suppressUnclearedConversations(
            String projectId, ClassifierRow signal, List<SubstrateObservation> candidates) {
        if (candidates.isEmpty()) return candidates;
        Set<String> traces =
                candidates.stream().map(SubstrateObservation::traceId).collect(Collectors.toSet());
        Set<String> flagged =
                detections.tracesInUnclearedFlaggedConversations(signal.detector(), projectId, signal.id(), traces);
        if (flagged.isEmpty()) return candidates;
        List<SubstrateObservation> kept =
                candidates.stream().filter(o -> !flagged.contains(o.traceId())).toList();
        StructuredLog.debug(log, "signal.sweep.conversation-suppressed")
                .message("skipped %d turn(s) in conversations already flagged", candidates.size() - kept.size())
                .field("signal", signal.classifierKey())
                .field("suppressed", candidates.size() - kept.size())
                .field("scored", kept.size())
                .log();
        return kept;
    }

    /**
     * The observation-grain sweep, drawing its candidate windows at {@code grain}.
     *
     * <p>One page per claim, except for a kind in {@link #DRAIN_TO_HEAD}, which keeps taking pages until one
     * comes back short, its drain budget runs out, or it loses the lease. The cadence stays one tick a minute
     * either way; what changes is how far one tick gets. Each page is persisted before the next is read and
     * armed after its cursor lands, so a failure part way loses at most the page in hand.
     */
    private void sweepObservationGrain(ClassifierJobRow job, ClassifierRow signal, Grain grain, Instant start) {
        // A detector with no registered DetectionTable has nowhere to write a fired detection, and
        // ClassifierDetectionWriteRepository's own javadoc says the caller checks writesDetections first. Applies
        // to CLASSIFIER and REGEX too, since both route through this same TURN/OBSERVATION grain and
        // share user_classifier_detection.
        if (!detections.writesDetections(signal.detector())) {
            StructuredLog.warn(log, Markers.OPS, "signal.sweep.no-table")
                    .message(
                            "no detection table is registered for detector kind %s; completing the job without"
                                    + " scoring",
                            signal.detector())
                    .field("job", job.id())
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("project", job.projectId())
                    .field("detector", signal.detector())
                    .field("grain", grain.name().toLowerCase(Locale.ROOT))
                    .log();
            jobs.markSwept(job.id(), null, null);
            sweepFailures.clear(job.id());
            return;
        }
        // writesDetections above guarantees a detector for this kind.
        BuiltInDetector detector = Objects.requireNonNull(catalog.detectorFor(signal.detector()));
        String detectorConfig = signal.configJson();
        boolean drain = DRAIN_TO_HEAD.contains(signal.detector());
        Duration budget = Duration.ofSeconds(props.getLeaseSeconds() / 2);
        int pageSize = BuiltInDetector.Kind.ENCODER_BACKED.contains(signal.detector())
                ? props.getEncoderBatchSize()
                : props.getBatchSize();

        String cursorAt = job.cursorAt();
        String cursorId = job.cursorId();
        int pages = 0;
        int scanned = 0;
        int fired = 0;
        boolean leaseLost = false;
        boolean atHead = false;
        // The first genuinely-new detection of this sweep, captured to drive the pre-deploy
        // registration exactly once per sweep, never per-event, so the trigger stays cheap even
        // when a whole drain fires.
        NewDetection firstNew = null;
        while (true) {
            Page page = sweepPage(job, signal, grain, detector, detectorConfig, cursorAt, cursorId, pageSize);
            if (page == null) {
                jobs.markSwept(job.id(), null, null);
                atHead = true;
                break;
            }
            pages++;
            scanned += page.scored();
            if (!page.advance()) {
                // Held or abandoned: nothing was written, so the cursor stays and the next tick re-reads
                // this page. Not at the head either, so the population work waits too.
                if (page.held()) {
                    jobs.holdPage(job.id());
                } else {
                    jobs.markSwept(job.id(), null, null);
                }
                break;
            }
            fired += page.fired();
            if (firstNew == null) firstNew = page.firstNew();
            cursorAt = page.cursorAt();
            cursorId = page.cursorId();
            boolean more = drain
                    && page.windowSize() >= pageSize
                    && Duration.between(start, Instant.now()).compareTo(budget) < 0;
            if (more) {
                leaseLost = !jobs.advanceCursor(job.id(), leaseOwner, cursorAt, cursorId, props.getLeaseSeconds());
            } else {
                jobs.markSwept(job.id(), cursorAt, cursorId);
                atHead = page.windowSize() < pageSize;
            }
            // The classifier's own arming, evaluated here rather than by an alerting worker reading the
            // detections back out: N in W opens or refreshes a finding with these spans as its evidence.
            // After the cursor lands and fail-soft, for the same reason: the detections have been written,
            // and a failure to roll them up must not make the sweep re-score the page.
            armIfConfigured(job.projectId(), signal, page.firedRefs(), Instant.now());
            if (!more || leaseLost) break;
        }
        sweepFailures.clear(job.id());
        if (atHead) {
            // Encoder-backed: remember when the sweep reached the head, so production mode can sleep
            // after it and the status can say when the last run was. Before the population work, so a
            // failure there cannot lose it.
            if (BuiltInDetector.Kind.ENCODER_BACKED.contains(signal.detector())) {
                jobs.recordCaughtUp(job.id(), leaseOwner, Instant.now());
            }
            catchUp(job, signal, cursorAt);
        }

        if (pages == 0) {
            // DEBUG: a sweep with no new observations is the steady state, not news. Together with
            // sweep.start this was 77% of all backend log volume, all of it saying nothing happened.
            StructuredLog.debug(log, "signal.sweep.empty")
                    .message("no new observations for %s since the last sweep", signal.classifierKey())
                    .field("job", job.id())
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("cursor", job.cursorId())
                    .log();
            return;
        }
        if (leaseLost) {
            StructuredLog.warn(log, Markers.OPS, "signal.sweep.lease-lost")
                    .message(
                            "%s lost its lease after %d page(s) of a drain; stopping so the new holder resumes"
                                    + " from the last page recorded",
                            signal.classifierKey(), pages)
                    .field("job", job.id())
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("project", job.projectId())
                    .field("pages", pages)
                    .log();
        }
        logSweepComplete(job, signal, grain, scanned, fired, pages, start);
        // A genuinely-new production signal discovery closes the loop to the edge: register a
        // routed, surface-scoped pre-deploy check so a future PR touching those surfaces is checked
        // pre-merge. Fired once per sweep off `firstNew`, not per-event, behind
        // tessary.predeploy.enabled, fail-soft.
        registerPreDeployCheck(job.projectId(), signal, firstNew);
    }

    /**
     * Hand a sweep that just caught up to the classifier's population work, if it has any. Fail-soft for the
     * reason arming is: the detections have landed and the cursor has moved, and a failure to roll them up must
     * not make the sweep re-score what it already checked.
     */
    private void catchUp(ClassifierJobRow job, ClassifierRow signal, @Nullable String checkedBefore) {
        catchUps.orderedStream()
                .filter(c -> c.kinds().contains(signal.detector()))
                .forEach(c -> {
                    try {
                        c.caughtUp(job, signal, checkedBefore);
                    } catch (RuntimeException e) {
                        StructuredLog.warn(log, Markers.OPS, "signal.catch-up.failed")
                                .field("project", job.projectId())
                                .field("signal", signal.classifierKey())
                                .field("classifierId", signal.id())
                                .field("error", e.getClass().getSimpleName())
                                .log();
                        log.debug("catch-up failure detail", e);
                    }
                });
    }

    /**
     * What one page of a sweep did.
     *
     * @param windowSize rows the cursor advanced over, which is how a drain tells a full page from the head
     * @param scored rows actually handed to the detector, after the grain's own filtering
     * @param advance false when nothing was written and the cursor must stay (a {@link PagedDetector} page
     *     held or abandoned)
     * @param held of a page that does not advance, whether it was held for a re-send rather than abandoned
     */
    private record Page(
            int windowSize,
            int scored,
            int fired,
            @Nullable NewDetection firstNew,
            List<FindingEvidenceRepository.Ref> firedRefs,
            String cursorAt,
            String cursorId,
            boolean advance,
            boolean held) {}

    /**
     * Score one page past {@code (cursorAt, cursorId)} and write what fired, or return null when there is
     * nothing past the cursor. Persists nothing about the job: the caller owns the cursor and the lease.
     */
    private @Nullable Page sweepPage(
            ClassifierJobRow job,
            ClassifierRow signal,
            Grain grain,
            BuiltInDetector detector,
            @Nullable String detectorConfig,
            @Nullable String cursorAt,
            @Nullable String cursorId,
            int pageSize) {
        // The window is what the cursor advances over, the same unfiltered stream at both grains so
        // the cursor always moves. `obs`, what actually gets scored, is the window minus the rows the
        // grain rejects, so a dropped row is never re-offered on a later tick.
        List<SubstrateObservation> window;
        List<SubstrateObservation> obs;
        if (grain == Grain.TURN) {
            List<SubstrateReadRepository.TurnCandidate> candidates =
                    substrate.turnCandidatesAfter(job.projectId(), cursorAt, cursorId, pageSize);
            window = candidates.stream()
                    .map(SubstrateReadRepository.TurnCandidate::observation)
                    .toList();
            obs = suppressUnclearedConversations(
                    job.projectId(),
                    signal,
                    oneScoredObservationPerTurn(candidates.stream()
                            .filter(SubstrateReadRepository.TurnCandidate::turnRoot)
                            .map(SubstrateReadRepository.TurnCandidate::observation)
                            .toList()));
        } else {
            window = substrate.observationsAfter(job.projectId(), cursorAt, cursorId, pageSize);
            obs = window;
        }
        if (window.isEmpty()) return null;
        if (detector instanceof PagedDetector<?> paged) {
            return pagedPage(job, signal, paged, window, obs);
        }

        int fired = 0;
        NewDetection firstNew = null;
        // What this page flagged, in sweep order, as evidence refs, handed to the arming gate so a
        // finding it opens is already pointing at the spans that opened it.
        List<FindingEvidenceRepository.Ref> firedRefs = new ArrayList<>();
        // Batch dispatch: deterministic detectors loop detect() internally; the encoder tier scores the
        // whole batch in one serving call.
        List<Detection> scored = detector.sweepBatch(signal, obs, detectorConfig);
        StructuredLog.info(log, Markers.OPS, "signal.sweep.detect")
                .field("job", job.id())
                .field("signal", signal.classifierKey())
                .field("classifierId", signal.id())
                .field("detector", signal.detector())
                .field("batchSize", obs.size())
                .log();
        for (int i = 0; i < obs.size(); i++) {
            Detection d = scored.get(i);
            if (!d.fired()) continue;
            // Write EVERY fired detection into this classifier's own table regardless of the
            // classifier's mode, stamping the confidence band; the discovery/tracking mode gate is a
            // READ-time filter, so flipping mode never loses history. Idempotent via the table's
            // own subject index, so a genuinely-new firing is the one that inserts.
            SubstrateObservation o = obs.get(i);
            NewDetection nd = writeDetection(job.projectId(), signal, o, d);
            if (nd != null) {
                if (firstNew == null) firstNew = nd;
                fired++;
                firedRefs.add(FindingEvidenceRepository.Ref.span(o.traceId(), o.observationId()));
            }
        }

        // Cursor advances over the WINDOW, not the scored subset: a per-turn duplicate dropped above must
        // stay dropped, not resurface as the first row of the next window and get scored after all.
        SubstrateObservation last = window.get(window.size() - 1);
        // The composite handle, not a bare span id. Span identity is (project_id, trace_id, id), and
        // the job table has two cursor columns for a three-part keyset, so both id halves ride
        // cursor_id as "<trace_id>:<span_id>", the spelling SubstrateReadRepository.parseHandle reads
        // back. A bare span id has no colon, parses as "no cursor", and the sweep then restarts from
        // page one on every tick forever: not a crash, just an unbounded re-scan that never advances.
        return new Page(
                window.size(),
                obs.size(),
                fired,
                firstNew,
                firedRefs,
                last.createdAt(),
                SubstrateReadRepository.handle(last.traceId(), last.observationId()),
                true,
                false);
    }

    /**
     * {@link #sweepPage} for a {@link PagedDetector}: score the page, let {@link PageRetryRule} decide what
     * becomes of it, then have the detector write what that allows. The detector writes its own detection
     * rows, so the ones it reports back are already genuinely new.
     */
    private <P extends PagedDetector.ScoredPage> Page pagedPage(
            ClassifierJobRow job,
            ClassifierRow signal,
            PagedDetector<P> detector,
            List<SubstrateObservation> window,
            List<SubstrateObservation> obs) {
        long started = System.nanoTime();
        P scoredPage = detector.score(signal, obs);
        PageAction action = PageRetryRule.decide(scoredPage, job.pageRetries(), detector.maxPageRetries());
        List<PagedDetector.FiredTurn> newlyFired =
                detector.complete(signal, scoredPage, action, (System.nanoTime() - started) / 1_000_000L);
        SubstrateObservation first = window.get(0);
        SubstrateObservation last = window.get(window.size() - 1);
        String cursorId = SubstrateReadRepository.handle(last.traceId(), last.observationId());
        if (action == PageAction.SKIP) {
            StructuredLog.warn(log, Markers.OPS, "signal.sweep.page-skipped")
                    .message(
                            "skipped a page of %s after %d holds: the provider stayed unavailable for most of it",
                            signal.classifierKey(), job.pageRetries())
                    .field("job", job.id())
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("project", job.projectId())
                    .field("from", SubstrateReadRepository.handle(first.traceId(), first.observationId()))
                    .field("to", cursorId)
                    .field("sent", scoredPage.sent())
                    .field("unavailable", scoredPage.unavailable())
                    .log();
        }
        boolean advance = action == PageAction.PERSIST || action == PageAction.SKIP || action == PageAction.PASS;
        NewDetection firstNew = null;
        List<FindingEvidenceRepository.Ref> firedRefs = new ArrayList<>(newlyFired.size());
        for (PagedDetector.FiredTurn f : newlyFired) {
            if (firstNew == null) firstNew = new NewDetection(f.detectionId(), f.severity());
            SubstrateObservation o = f.turn();
            firedRefs.add(FindingEvidenceRepository.Ref.trace(o.traceId()));
        }
        return new Page(
                window.size(),
                obs.size(),
                newlyFired.size(),
                firstNew,
                firedRefs,
                last.createdAt(),
                cursorId,
                advance,
                action == PageAction.HOLD);
    }

    /**
     * Hand this sweep's firings to the arming gate. A classifier with no {@code arming} block in its
     * config does nothing here: a user's classifier ships unarmed, and only a built-in whose bar the
     * platform authored arrives with one.
     */
    private void armIfConfigured(
            String projectId, ClassifierRow signal, List<FindingEvidenceRepository.Ref> firedRefs, Instant now) {
        try {
            arming.evaluate(signal, projectId, firedRefs, now);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "signal.arming.failed")
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field("error", e.getMessage())
                    .log();
        }
    }

    /** One field set for every grain, so a Loki query does not have to know which sweep ran. */
    private void logSweepComplete(
            ClassifierJobRow job, ClassifierRow signal, Grain grain, int scanned, int fired, int pages, Instant start) {
        StructuredLog.info(log, Markers.OPS, "signal.sweep.complete")
                .message(
                        "swept %d observation(s) for %s at %s grain over %d page(s), %d fired",
                        scanned, signal.classifierKey(), grain.name().toLowerCase(Locale.ROOT), pages, fired)
                .field("job", job.id())
                .field("signal", signal.classifierKey())
                .field("classifierId", signal.id())
                .field("project", job.projectId())
                .field("grain", grain.name().toLowerCase(Locale.ROOT))
                .field("scanned", scanned)
                .field("fired", fired)
                .field("pages", pages)
                .durationMs(start)
                .log();
    }

    /**
     * At most one scored observation per turn, keeping the earliest by the window's {@code
     * (created_at, id)} order. The structural turn-root filter already drops inner calls and the
     * {@code agent}/{@code llm} twin, so this only bites when a producer emits several parentless
     * root spans under one turn: several sequential top-level LLM calls answering one user message.
     * Those are one user utterance, and the classifier must speak once about it.
     *
     * <p>Scope is the window, not all history: frustration's detection table keys on the trace, so a
     * turn whose roots straddle a batch boundary is a write-time conflict rather than something this
     * filter has to catch, and it's left doing the one job it's right for, not scoring the same turn
     * several times inside one batch.
     *
     * <p>An observation with no turn context is never folded into another; it keys on its own id, so
     * an unparented span stays its own unit rather than colliding with one.
     */
    private static List<SubstrateObservation> oneScoredObservationPerTurn(List<SubstrateObservation> window) {
        Set<String> seenTurns = new HashSet<>();
        List<SubstrateObservation> kept = new ArrayList<>(window.size());
        for (SubstrateObservation o : window) {
            if (seenTurns.add(o.traceId())) kept.add(o);
        }
        return kept;
    }

    /**
     * Write a fired detection into this classifier's own table, carrying the producer subject the
     * detection is about (the session it belongs to, null for anonymous traffic, its trace, and its
     * span) plus the detector's severity, confidence band, and evidence.
     *
     * <p>The turn-grain duplicate is fixed by the table, not a lookup: the detection table's unique
     * key is the trace, the grain the classifier judges at, so a second parentless root span under
     * one turn conflicts on write rather than needing to be checked for.
     *
     * <p>Fail-soft: a persistence failure degrades to a logged warning so one bad detection never
     * fails the sweep or blocks the cursor.
     *
     * @return the new detection, or null when this classifier had already spoken about this subject
     */
    private @Nullable NewDetection writeDetection(
            String projectId, ClassifierRow signal, SubstrateObservation o, Detection d) {
        String detectionId = Ids.ulid();
        try {
            boolean inserted = detections.insert(
                    detectionId,
                    signal.detector(),
                    projectId,
                    signal.id(),
                    signal.classifierKey(),
                    o.projectVersionId(),
                    o.sessionId(),
                    o.traceId(),
                    o.observationId(),
                    d.severity(),
                    d.confidence(),
                    d.evidenceJson());
            // First-write-wins: a genuinely-new detection is the one that inserted.
            return inserted ? new NewDetection(detectionId, d.severity()) : null;
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "signal.detection.failed")
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field("trace", o.traceId())
                    .field("span", o.observationId())
                    .field("error", e.getMessage())
                    .log();
            return null;
        }
    }

    /** A newly-inserted signal detection: its stable detection id + coarse severity. */
    private record NewDetection(
            String detectionId, @Nullable String severity) {}

    /**
     * Close the production → pre-deploy loop: on a genuinely-new discovery, register a routed
     * pre-deploy check for the classifier's implicated surfaces. No-op when nothing new fired this sweep or
     * the loop is disabled. Maps the {@link ClassifierRow} to the {@code ci}-package input record at the call
     * site so the {@code signal → ci} dependency stays one-way. Fail-soft: a failure here is logged and
     * never fails the sweep or blocks the cursor (the discovery itself already landed).
     */
    private void registerPreDeployCheck(String projectId, ClassifierRow signal, @Nullable NewDetection firstNew) {
        if (firstNew == null || !preDeployChecks.isEnabled()) return;
        try {
            preDeployChecks.registerForSignal(ClassifierDiscovery.of(
                    projectId, signal.id(), signal.classifierKey(), signal.configJson(), firstNew.severity()));
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "signal.predeploy-registration.failed")
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field("error", e.getMessage())
                    .log();
        }
    }

    @ExcludeFromJacocoGeneratedReport(
            "asks the OS for the local host name; only a machine whose own name does not resolve reaches the catch")
    private static String shortHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "host";
        }
    }
}
