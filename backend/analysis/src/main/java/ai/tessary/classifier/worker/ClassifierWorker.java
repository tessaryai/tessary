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
import ai.tessary.open.jobqueue.LeasedJobSql;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.RepeatedFailureLogger;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * The async classifier worker: each heartbeat dead-letters poison jobs, enqueues a sweep per enabled signal for
 * projects with observations (a project with no spans would sweep an empty window forever), and dispatches claimed jobs
 * ({@code FOR UPDATE SKIP LOCKED}) to the bounded {@code signalTaskExecutor}. Strictly off the ingest hot path.
 */
@Component
public class ClassifierWorker {

    private static final Logger log = LoggerFactory.getLogger(ClassifierWorker.class);

    private static final int MAX_CLAIM_ROUNDS = 10_000;
    // The bounded executor backpressures dispatch; this only bounds how many job rows one claim flips.
    private static final int CLAIM_BATCH = 5;

    /**
     * Kinds whose backlog arrives at once (a backfill, or Malformed Output rewinding when a schema lands), so a sweep
     * keeps paging inside one claim until the head. Groundedness drains at {@code encoder-batch-size} pages so each
     * finishes inside the lease, and the scorer caps in-flight requests.
     */
    private static final Set<String> DRAIN_TO_HEAD = Set.of(
            BuiltInDetector.Kind.SECRET_LEAK, BuiltInDetector.Kind.MALFORMED_OUTPUT, BuiltInDetector.Kind.GROUNDEDNESS);

    // A job failing every tick gets one stacktrace, then a summary every 30 occurrences.
    private static final int SWEEP_FAILURE_SUMMARY_EVERY = 30;

    private final ClassifierService signalService;
    private final ClassifierRepository signals;
    private final ClassifierJobRepository jobs;
    private final ClassifierDetectionWriteRepository detections;
    private final ClassifierArming arming;
    private final SubstrateReadRepository substrate;
    private final BuiltInClassifierCatalog catalog;
    /** A classifier attaches by being on the classpath. */
    private final ClassifierSweepRegistry sweeps;

    private final SweepRouting routing;

    private final ObjectProvider<ClassifierCatchUp> catchUps;

    private final ClassifierProperties props;
    private final TaskExecutor executor;
    private final TraceMdcBridge traceBridge;
    private final String leaseOwner =
            LeasedJobSql.leaseOwner(() -> InetAddress.getLocalHost().getHostName());
    private final RepeatedFailureLogger sweepFailures = new RepeatedFailureLogger(SWEEP_FAILURE_SUMMARY_EVERY);

    public ClassifierWorker(
            ClassifierService signalService,
            ClassifierRepository signals,
            ClassifierJobRepository jobs,
            ClassifierDetectionWriteRepository detections,
            ClassifierArming arming,
            SubstrateReadRepository substrate,
            BuiltInClassifierCatalog catalog,
            ClassifierSweepRegistry sweeps,
            SweepRouting routing,
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
        this.sweeps = sweeps;
        this.routing = routing;
        this.catchUps = catchUps;
        this.props = props;
        this.traceBridge = traceBridge;
        this.executor = executor;
    }

    /** The worker dispatches a kind through only one of them, so the other would silently stop scoring. */
    @PostConstruct
    void validateRouting() {
        List<String> both = sweeps.registeredKinds().stream()
                .filter(kind -> catalog.detectorFor(kind) != null)
                .sorted()
                .toList();
        if (!both.isEmpty()) {
            throw new IllegalStateException("detector kind(s) " + both
                    + " have both a BuiltInDetector and a ClassifierSweep; a kind is dispatched through"
                    + " one of them, so remove the other");
        }
    }

    /**
     * The default initial delay is 0, so the first tick runs at startup; tests that drive {@code tick()} delay it past
     * the run.
     */
    @Scheduled(
            fixedDelayString = "${tessary.classifier.heartbeat-ms:60000}",
            initialDelayString = "${tessary.classifier.initial-delay-ms:0}")
    public void tick() {
        // Spring's @Scheduled span never reaches MDC on the scheduler thread by itself; bind it so sweep logs carry
        // this tick's trace id.
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        try {
            int exhausted = jobs.failExhausted(props.maxAttempts());
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
                batch = jobs.claimBatch(leaseOwner, CLAIM_BATCH, props.leaseSeconds(), props.maxAttempts());
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

    private static boolean isEncoderUnreachable(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof EncoderUnreachableException) return true;
        }
        return false;
    }

    String leaseOwnerForTest() {
        return leaseOwner;
    }

    void sweepForTest(ClassifierJobRow job) {
        sweep(job);
    }

    /** A kind with no detection table completes without scoring, so it does not re-scan forever. */
    private void sweep(ClassifierJobRow job) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put(LogContext.PROJECT_ID, job.projectId());
        // Structured so Loki can filter on {jobId="..."}.
        ctx.put(LogContext.JOB_ID, job.id());
        Instant start = Instant.now();
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
                jobs.markSwept(job.id(), null, null);
                sweepFailures.clear(job.id());
                return;
            }
            ClassifierRow signal = maybe.get();
            classifierKey = signal.classifierKey();
            if (signalService.encoderDown(signal)) {
                // Pending from before the model went away: hand it back unrun and uncounted.
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
                // DEBUG: it announces intent, every fact recurs on the completion line, and it was 45% of production
                // log volume.
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
                // The model is asleep or stopped, not broken, so no attempt is spent; a 5xx or a 401 still
                // dead-letters.
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
            // One failing sweep is a deduped WARN; the alertable ERROR is the dead-letter transition. Clearing the
            // streak on dead-letter makes each failed revival probe log fresh.
            boolean deadLettered = jobs.markFailed(job.id(), e.getMessage(), props.maxAttempts());
            final @Nullable String readableSignal = classifierKey;
            if (deadLettered) {
                sweepFailures.clear(job.id());
                StructuredLog.error(log, Markers.OPS, "signal.sweep.dead-lettered")
                        .field("job", job.id())
                        .field("signal", readableSignal)
                        .field("classifierId", job.classifierId())
                        .field("attempts", props.maxAttempts())
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
     * The grain decides which seam runs. WINDOW-grain classifiers score against fitted per-project state but reuse this
     * job's cursor, lease and dead-letter budget. Which sweep runs is a {@link ClassifierSweepRegistry} lookup; see
     * {@link SweepRouting} for undeclared kinds.
     */
    private void sweepSignal(ClassifierJobRow job, ClassifierRow signal, Instant start) {
        SweepRouting.Routing route = routing.routeFor(signal.detector());
        Grain grain = route.grain();
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
                            .field("route", route.route().field())
                            .field("registered", String.join(",", sweeps.registeredKinds()))
                            .log();
                    jobs.markSwept(job.id(), null, null);
                    sweepFailures.clear(job.id());
                    return;
                }
                SweepOutcome outcome = sweep.get().sweep(new SweepContext(job, signal));
                sweepFailures.clear(job.id());
                logSweepComplete(job, signal, grain, route.route(), outcome.scanned(), outcome.fired(), 1, start);
            }
            case TURN, OBSERVATION -> sweepObservationGrain(job, signal, grain, start);
        }
    }

    /**
     * A session with an uncleared detection is not scored again: the operator acts on the session, one event, not one
     * per turn. A session is per call site.
     */
    private List<SubstrateObservation> suppressUnclearedSessions(
            String projectId, ClassifierRow signal, List<SubstrateObservation> candidates) {
        if (candidates.isEmpty()) return candidates;
        Set<ClassifierDetectionWriteRepository.CallSiteTurn> turns =
                candidates.stream().map(ClassifierWorker::callSiteTurn).collect(Collectors.toSet());
        Set<ClassifierDetectionWriteRepository.CallSiteTurn> flagged =
                detections.unclearedFlaggedSessions(signal.detector(), projectId, signal.id(), turns);
        if (flagged.isEmpty()) return candidates;
        List<SubstrateObservation> kept = candidates.stream()
                .filter(o -> !flagged.contains(callSiteTurn(o)))
                .toList();
        StructuredLog.debug(log, "signal.sweep.conversation-suppressed")
                .message("skipped %d turn(s) in sessions already flagged", candidates.size() - kept.size())
                .field("signal", signal.classifierKey())
                .field("suppressed", candidates.size() - kept.size())
                .field("scored", kept.size())
                .log();
        return kept;
    }

    private static ClassifierDetectionWriteRepository.CallSiteTurn callSiteTurn(SubstrateObservation o) {
        return new ClassifierDetectionWriteRepository.CallSiteTurn(
                o.traceId(), Objects.requireNonNull(o.callSiteId(), "a turn candidate has a call site"));
    }

    /** A span with no call site of its own takes its trace's, as the traces list filters. */
    private List<SubstrateObservation> inCallSiteScope(
            String projectId, ClassifierRow signal, List<SubstrateObservation> candidates) {
        if (signal.callSiteIds() == null || candidates.isEmpty()) return candidates;
        Set<String> untaggedTraces = candidates.stream()
                .filter(o -> o.callSiteId() == null)
                .map(SubstrateObservation::traceId)
                .collect(Collectors.toSet());
        Map<String, Set<String>> traceCallSites = substrate.callSitesByTrace(projectId, untaggedTraces);
        List<SubstrateObservation> kept = candidates.stream()
                .filter(o -> o.callSiteId() != null
                        ? signal.runsOn(o.callSiteId())
                        : traceCallSites.getOrDefault(o.traceId(), Set.of()).stream()
                                .anyMatch(signal::runsOn))
                .toList();
        if (kept.size() < candidates.size()) {
            StructuredLog.debug(log, "signal.sweep.call-site-scoped")
                    .message(
                            "skipped %d observation(s) outside %s's call sites",
                            candidates.size() - kept.size(), signal.classifierKey())
                    .field("signal", signal.classifierKey())
                    .field("skipped", candidates.size() - kept.size())
                    .field("scored", kept.size())
                    .log();
        }
        return kept;
    }

    /**
     * One page per claim, except kinds in {@link #DRAIN_TO_HEAD}, which page until a short page, the drain budget or a
     * lost lease. Each page persists before the next is read, so a failure loses at most the page in hand.
     */
    private void sweepObservationGrain(ClassifierJobRow job, ClassifierRow signal, Grain grain, Instant start) {
        // A detector with no DetectionTable has nowhere to write; CLASSIFIER and REGEX share user_classifier_detection.
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
        // writesDetections guarantees a detector for this kind.
        BuiltInDetector detector = Objects.requireNonNull(catalog.detectorFor(signal.detector()));
        String detectorConfig = signal.configJson();
        boolean drain = DRAIN_TO_HEAD.contains(signal.detector());
        Duration budget = Duration.ofSeconds(props.leaseSeconds() / 2);
        int pageSize = BuiltInDetector.Kind.ENCODER_BACKED.contains(signal.detector())
                ? props.encoderBatchSize()
                : props.batchSize();

        String cursorAt = job.cursorAt();
        String cursorId = job.cursorId();
        int pages = 0;
        int scanned = 0;
        int fired = 0;
        boolean leaseLost = false;
        boolean atHead = false;
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
                // Nothing was written, so the cursor stays and the next tick re-reads this page.
                if (page.held()) {
                    jobs.holdPage(job.id());
                } else {
                    jobs.markSwept(job.id(), null, null);
                }
                break;
            }
            fired += page.fired();
            cursorAt = page.cursorAt();
            cursorId = page.cursorId();
            boolean more = drain
                    && page.windowSize() >= pageSize
                    && Duration.between(start, Instant.now()).compareTo(budget) < 0;
            if (more) {
                leaseLost = !jobs.advanceCursor(job.id(), leaseOwner, cursorAt, cursorId, props.leaseSeconds());
            } else {
                jobs.markSwept(job.id(), cursorAt, cursorId);
                atHead = page.windowSize() < pageSize;
            }
            // N in W opens or refreshes a finding with these spans as evidence. After the cursor lands and fail-soft: a
            // roll-up failure must not re-score the page.
            armIfConfigured(job.projectId(), signal, page.firedRefs(), Instant.now());
            if (!more || leaseLost) break;
        }
        sweepFailures.clear(job.id());
        if (atHead) {
            // Recorded before the population work, so a failure there cannot lose it; production mode sleeps off it.
            if (BuiltInDetector.Kind.ENCODER_BACKED.contains(signal.detector())) {
                jobs.recordCaughtUp(job.id(), leaseOwner, Instant.now());
            }
            catchUp(job, signal, cursorAt);
        }

        if (pages == 0) {
            // DEBUG: an empty sweep is the steady state.
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
        logSweepComplete(job, signal, grain, SweepRouting.Route.CATALOG, scanned, fired, pages, start);
    }

    /** Fail-soft, as arming is: the detections and cursor have landed. */
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
     * {@code windowSize} tells a full page from the head; {@code advance} is false when the cursor must stay;
     * {@code held} tells a page held for re-send from one abandoned.
     */
    private record Page(
            int windowSize,
            int scored,
            int fired,
            List<FindingEvidenceRepository.Ref> firedRefs,
            String cursorAt,
            String cursorId,
            boolean advance,
            boolean held) {}

    /** The caller owns the cursor and the lease. */
    private @Nullable Page sweepPage(
            ClassifierJobRow job,
            ClassifierRow signal,
            Grain grain,
            BuiltInDetector detector,
            @Nullable String detectorConfig,
            @Nullable String cursorAt,
            @Nullable String cursorId,
            int pageSize) {
        // The cursor advances over the unfiltered window so it always moves; a row the grain rejects is never
        // re-offered.
        List<SubstrateObservation> window;
        List<SubstrateObservation> obs;
        if (grain == Grain.TURN) {
            List<SubstrateReadRepository.TurnCandidate> candidates =
                    substrate.turnCandidatesAfter(job.projectId(), cursorAt, cursorId, pageSize);
            window = candidates.stream()
                    .map(SubstrateReadRepository.TurnCandidate::observation)
                    .toList();
            obs = suppressUnclearedSessions(
                    job.projectId(),
                    signal,
                    inCallSiteScope(
                            job.projectId(),
                            signal,
                            candidates.stream()
                                    .filter(SubstrateReadRepository.TurnCandidate::opensCallSiteTurn)
                                    .map(SubstrateReadRepository.TurnCandidate::observation)
                                    .toList()));
        } else {
            window = substrate.observationsAfter(job.projectId(), cursorAt, cursorId, pageSize);
            obs = inCallSiteScope(job.projectId(), signal, window);
        }
        if (window.isEmpty()) return null;
        if (detector instanceof PagedDetector<?> paged) {
            return pagedPage(job, signal, paged, window, obs);
        }

        int fired = 0;
        // Handed to the arming gate so a finding it opens already cites the spans that opened it.
        List<FindingEvidenceRepository.Ref> firedRefs = new ArrayList<>();
        // A page the call-site scope emptied sends nothing, but still moves the cursor.
        List<Detection> scored = List.of();
        if (!obs.isEmpty()) {
            scored = detector.sweepBatch(signal, obs, detectorConfig);
            StructuredLog.info(log, Markers.OPS, "signal.sweep.detect")
                    .field("job", job.id())
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("detector", signal.detector())
                    .field("batchSize", obs.size())
                    .log();
        }
        for (int i = 0; i < obs.size(); i++) {
            Detection d = scored.get(i);
            if (!d.fired()) continue;
            // Every fired detection is written regardless of mode, since the mode gate is a read-time filter.
            // Idempotent on the table's subject index.
            SubstrateObservation o = obs.get(i);
            if (writeDetection(job.projectId(), signal, o, d)) {
                fired++;
                firedRefs.add(FindingEvidenceRepository.Ref.span(o.traceId(), o.observationId()));
            }
        }

        // Over the WINDOW, so a dropped per-turn duplicate is not scored as the next window's first row.
        SubstrateObservation last = window.get(window.size() - 1);
        // The composite "<trace_id>:<span_id>" handle: a bare span id parses as "no cursor" and the sweep would re-scan
        // from page one forever.
        return new Page(
                window.size(),
                obs.size(),
                fired,
                firedRefs,
                last.createdAt(),
                SubstrateReadRepository.handle(last.traceId(), last.observationId()),
                true,
                false);
    }

    /** The detector writes its own rows, so what it reports back is already new. */
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
        List<FindingEvidenceRepository.Ref> firedRefs = new ArrayList<>(newlyFired.size());
        for (PagedDetector.FiredTurn f : newlyFired) {
            SubstrateObservation o = f.turn();
            firedRefs.add(FindingEvidenceRepository.Ref.trace(o.traceId()));
        }
        return new Page(
                window.size(),
                obs.size(),
                newlyFired.size(),
                firedRefs,
                last.createdAt(),
                cursorId,
                advance,
                action == PageAction.HOLD);
    }

    /** Only built-ins whose bar the platform authored arrive with an {@code arming} block. */
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

    private void logSweepComplete(
            ClassifierJobRow job,
            ClassifierRow signal,
            Grain grain,
            SweepRouting.Route route,
            int scanned,
            int fired,
            int pages,
            Instant start) {
        StructuredLog.info(log, Markers.OPS, "signal.sweep.complete")
                .message(
                        "swept %d observation(s) for %s at %s grain over %d page(s), %d fired",
                        scanned, signal.classifierKey(), grain.name().toLowerCase(Locale.ROOT), pages, fired)
                .field("job", job.id())
                .field("signal", signal.classifierKey())
                .field("classifierId", signal.id())
                .field("project", job.projectId())
                .field("grain", grain.name().toLowerCase(Locale.ROOT))
                .field("route", route.field())
                .field("scanned", scanned)
                .field("fired", fired)
                .field("pages", pages)
                .durationMs(start)
                .log();
    }

    /**
     * Fail-soft: one bad detection must not fail the sweep or block the cursor. Null when this classifier already spoke
     * about the subject.
     */
    private boolean writeDetection(String projectId, ClassifierRow signal, SubstrateObservation o, Detection d) {
        try {
            return detections.insert(
                    Ids.ulid(),
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
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "signal.detection.failed")
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field("trace", o.traceId())
                    .field("span", o.observationId())
                    .field("error", e.getMessage())
                    .log();
            return false;
        }
    }
}
