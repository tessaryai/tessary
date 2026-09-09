// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.gate.PreDeployCheckService;
import ai.tessary.gate.PreDeployCheckService.ClassifierDiscovery;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.RepeatedFailureLogger;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The async signal-detection worker. On an operational heartbeat it dead-letters poison jobs,
 * enqueues a sweep job per enabled signal for every project that has substrate observations, then claims
 * due jobs ({@code FOR UPDATE SKIP LOCKED}) and dispatches each to the bounded {@code signalTaskExecutor}.
 * Each sweep reads observations past the classifier's cursor, runs the built-in detector, writes signal
 * detections as verdicts idempotently, and advances the cursor.
 *
 * <p><b>This worker no longer provisions the catalog.</b> Which built-ins a project HAS is
 * {@code ClassifierCatalogWorker}'s job, on its own {@code tessary.classifier.catalog-resync-ms}
 * heartbeat over every ACTIVE project; which of them SWEEP is this one's, and that scope is
 * legitimately "projects with observations" because a job on a project with no spans would be claimed
 * and dispatched to sweep an empty window every tick forever. Running the provisioning pass here made
 * the two scopes one, so a capability flag could not reach a project until that project's first span
 * landed — see {@code ClassifierCatalogWorker}'s javadoc for the failure it caused.
 *
 * <p>Strictly off the ingest hot path: it reads what the substrate write path produced; it is
 * NEVER hooked into {@code SubstrateWriter.enqueue}. The worker itself is always on, and every
 * project is seeded, so participation is decided only by which built-ins the tenant left enabled.
 */
@Component
public class ClassifierWorker {

    private static final Logger log = LoggerFactory.getLogger(ClassifierWorker.class);

    private static final int MAX_CLAIM_ROUNDS = 10_000;
    // Jobs claimed per round; the bounded signalTaskExecutor backpressures dispatch, so this only
    // bounds how many job rows one UPDATE...SKIP LOCKED flips at once (not the per-sweep batch size).
    private static final int CLAIM_BATCH = 5;
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
     * Every classifier sweep on this classpath, by the detector kind it claims. One bean where four
     * concrete sweeps used to be fields: the worker no longer knows which classifiers exist, which is
     * what lets a classifier ship from a jar this reactor does not build.
     */
    private final ClassifierSweepRegistry sweeps;

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

    /** Test seam: run the production {@link #sweep} for one job directly (mirrors MeteringWorker's analogous seam). */
    void sweepForTest(ClassifierJobRow job) {
        sweep(job);
    }

    /**
     * Run one classifier's sweep: load its (enabled) definition, dispatch its detector over observations
     * past the cursor, write fired detections idempotently, advance the cursor to the last observation
     * seen. An inert/unknown detector is a cursor-advancing no-op (so it doesn't re-scan forever).
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
                jobs.markSwept(job.id(), null, null); // signal removed/disabled — leave the cursor, finish
                sweepFailures.clear(job.id());
                return;
            }
            ClassifierRow signal = maybe.get();
            classifierKey = signal.classifierKey();
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
            // Severity policy (gh#532) meets the dead-letter budget (gh#531): one sweep failing is a WARN
            // (deduped — first of a streak carries the stacktrace, repeats collapse to a summary), and the
            // alertable ERROR is reserved for the budget-exhausted dead-letter transition. Clearing the
            // streak on dead-letter means each post-cooldown revival probe that fails again logs fresh.
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
     * The body of one sweep, once the signal is resolved and its {@code classifierKey} is bound to MDC. The
     * grain enum is the single source of truth for which SEAM runs: the fitting tier (TRACE and WINDOW)
     * scores a unit larger than an observation against fitted per-project state, so it cannot ride the
     * observation-grain detector seam — but it reuses this job's cursor, lease, and dead-letter budget
     * verbatim. There is no new scheduler and no new job table for any of it.
     *
     * <p>WHICH sweep runs inside that seam is a lookup, not an {@code if/else} chain. This method used
     * to name {@code BehaviorDriftSweep} and {@code ConformanceSweep} directly, which made the open
     * engine uncompilable without both paid classifiers; now it holds
     * {@link ClassifierSweepRegistry} and a classifier attaches by being on the classpath.
     */
    private void sweepSignal(ClassifierJobRow job, ClassifierRow signal, Instant start) {
        Grain grain = catalog.grainFor(signal.detector());
        switch (grain) {
            case TRACE, WINDOW -> {
                Optional<ClassifierSweep> sweep = sweeps.forKind(signal.detector());
                if (sweep.isEmpty()) {
                    // INERT, and three things it deliberately is not.
                    //
                    // Not a throw and not a dead-letter: this job is re-pended by every heartbeat, so a
                    // failing branch here would burn the attempt budget of a project whose only fault is
                    // running an edition that does not ship this classifier, forever.
                    //
                    // Above all not a fallthrough. The arm this replaced ended `else -> metricSweep`, so
                    // any WINDOW kind without a case of its own silently ran metric drift instead of its
                    // own analysis. `tool_error` did exactly that once: its config blob names no
                    // `measures`, MetricDriftConfig fell back to the full default set, and it kept a
                    // second copy of every duration and cost baseline and emitted a duplicate finding per
                    // drift under its own classifier id. Nothing failed loudly.
                    //
                    // And the classifier is NOT retired for it. Absence of a sweep says nothing about
                    // catalog membership — see ClassifierService#retireDroppedBuiltIns, where leaving the
                    // catalog is permanent.
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
                logSweepComplete(job, signal, grain, outcome.scanned(), outcome.fired(), start);
            }
            case TURN, OBSERVATION -> sweepObservationGrain(job, signal, grain, start);
        }
    }

    /**
     * Drop turns whose CONVERSATION this classifier has already flagged at the HIGH band.
     *
     * <p>A turn-grain classifier's subject is what the user said, but the thing an operator acts on is
     * the CONVERSATION — and a conversation is one event, not one per turn. interview-coach carried
     * 8,012 frustration detections over 987 conversations (2026-08-20): 8.12 rows per conversation,
     * each one a separate encoder call, all saying the same thing about the same conversation.
     *
     * <p>HIGH is the ceiling, which is what makes this safe to skip rather than merely de-duplicate on
     * write: no later turn can move a conversation that is already flagged at the top band, so scoring
     * one is work with no possible outcome. A conversation flagged only at LOW is deliberately still
     * scored, so it can escalate — suppressing on any band would freeze a mild early turn as the
     * conversation's final answer and hide the severe turn that came after it.
     *
     * <p>Turns with no session id are never suppressed: a trace that belongs to no conversation has no
     * conversation to have been flagged, and {@code sessionId} is legitimately null for producers that
     * send none (see {@link SubstrateObservation}).
     *
     * <p>This does NOT narrow what the classifier flags — a conversation still gets flagged the first
     * time it earns it. It removes repeat rows about conversations already flagged, which is a volume
     * and cost property, not a precision one: a signal firing on three quarters of conversations still
     * fires on three quarters of them after this.
     */
    private List<SubstrateObservation> suppressAlreadyFlaggedConversations(
            String projectId, ClassifierRow signal, List<SubstrateObservation> candidates) {
        Set<String> sessions = candidates.stream()
                .map(SubstrateObservation::sessionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (sessions.isEmpty()) return candidates;
        Set<String> flagged =
                detections.sessionsAlreadyFlaggedHigh(signal.detector(), projectId, signal.id(), sessions);
        if (flagged.isEmpty()) return candidates;
        List<SubstrateObservation> kept = candidates.stream()
                .filter(o -> o.sessionId() == null || !flagged.contains(o.sessionId()))
                .toList();
        StructuredLog.debug(log, "signal.sweep.conversation-suppressed")
                .message("skipped %d turn(s) in conversations already flagged at high", candidates.size() - kept.size())
                .field("signal", signal.classifierKey())
                .field("suppressed", candidates.size() - kept.size())
                .field("scored", kept.size())
                .log();
        return kept;
    }

    /** The observation-grain sweep, drawing its candidate window at {@code grain}. */
    private void sweepObservationGrain(ClassifierJobRow job, ClassifierRow signal, Grain grain, Instant start) {
        // A detector with no registered DetectionTable has nowhere to write a fired detection —
        // ClassifierDetectionWriteRepository#insert throws IllegalArgumentException for exactly this
        // case, and the writer's own javadoc says the caller checks writesDetections first. Before
        // this gate, nothing here actually did: only ClassifierArming's config read did. This applies
        // to CLASSIFIER and REGEX too, since both route through this same TURN/OBSERVATION grain and
        // both share user_classifier_detection — see OpenDetectionTables, which registers both.
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
        BuiltInDetector detector = catalog.detectorFor(signal.detector());
        String detectorConfig = signal.configJson();

        // The window is what the CURSOR advances over, and it is the same unfiltered stream at both
        // grains so the cursor always moves. `obs` — what actually gets scored — is the window minus the
        // rows the grain rejects, so a dropped row is never re-offered on a later tick.
        List<SubstrateObservation> window;
        List<SubstrateObservation> obs;
        if (grain == Grain.TURN) {
            List<SubstrateReadRepository.TurnCandidate> candidates = substrate.turnCandidatesAfter(
                    job.projectId(), job.cursorAt(), job.cursorId(), props.getBatchSize());
            window = candidates.stream()
                    .map(SubstrateReadRepository.TurnCandidate::observation)
                    .toList();
            obs = suppressAlreadyFlaggedConversations(
                    job.projectId(),
                    signal,
                    oneScoredObservationPerTurn(candidates.stream()
                            .filter(SubstrateReadRepository.TurnCandidate::turnRoot)
                            .map(SubstrateReadRepository.TurnCandidate::observation)
                            .toList()));
        } else {
            window = substrate.observationsAfter(job.projectId(), job.cursorAt(), job.cursorId(), props.getBatchSize());
            obs = window;
        }
        if (window.isEmpty()) {
            // DEBUG: a sweep with no new observations is the steady state, not news. Together with
            // sweep.start this was 77% of all backend log volume, all of it saying nothing happened.
            StructuredLog.debug(log, "signal.sweep.empty")
                    .message("no new observations for %s since the last sweep", signal.classifierKey())
                    .field("job", job.id())
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("cursor", job.cursorId())
                    .log();
            jobs.markSwept(job.id(), null, null);
            sweepFailures.clear(job.id());
            return;
        }

        Instant nowInstant = Instant.now();
        int fired = 0;
        // The first genuinely-new detection of this sweep, captured to drive the pre-deploy
        // registration exactly once per sweep — never per-event — so the trigger stays cheap
        // even when a whole batch fires (perf: no per-event work, no risk-model rank).
        NewDetection firstNew = null;
        // What this sweep flagged, in sweep order, as evidence refs — handed to the arming gate so a
        // finding it opens is already pointing at the spans that opened it.
        List<FindingEvidenceRepository.Ref> firedRefs = new ArrayList<>();
        if (detector != null) {
            // Batch dispatch: deterministic detectors loop detect() internally; the encoder tier
            // scores the whole batch in one serving call. Inert/unknown kinds (detector == null)
            // advance the cursor only.
            List<Detection> scored = detector.detectBatch(obs, detectorConfig);
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
                    firedRefs.add(
                            grain == Grain.TURN
                                    ? FindingEvidenceRepository.Ref.trace(o.traceId())
                                    : FindingEvidenceRepository.Ref.span(o.traceId(), o.observationId()));
                }
            }
        } else {
            StructuredLog.info(log, Markers.OPS, "signal.sweep.inert-detector")
                    .field("job", job.id())
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("detector", signal.detector())
                    .log();
        }

        // Cursor advances over the WINDOW, not the scored subset: a per-turn duplicate dropped above must
        // stay dropped, not resurface as the first row of the next window and get scored after all.
        SubstrateObservation last = window.get(window.size() - 1);
        // The COMPOSITE handle, not a bare span id. Span identity is (project_id, trace_id, id), and the
        // job table has two cursor columns for a three-part keyset, so both id halves ride cursor_id as
        // "<trace_id>:<span_id>" — the spelling SubstrateReadRepository.parseHandle reads back. A bare
        // span id has no colon, parses as "no cursor", and the sweep then restarts from page one on
        // every tick forever: not a crash, just an unbounded re-scan that never advances.
        jobs.markSwept(
                job.id(), last.createdAt(), SubstrateReadRepository.handle(last.traceId(), last.observationId()));
        sweepFailures.clear(job.id());
        logSweepComplete(job, signal, grain, obs.size(), fired, start);
        // The classifier's OWN arming, evaluated here rather than by an alerting worker reading the
        // detections back out: N in W opens or refreshes a finding with these spans as its evidence.
        // Fail-soft for the same reason everything else after the cursor advance is — the detections
        // have landed, and a failure to roll them up must not make the sweep re-score the window.
        armIfConfigured(job.projectId(), signal, firedRefs, nowInstant);
        // A genuinely-NEW production signal discovery closes the loop to the edge — register a
        // routed, surface-scoped pre-deploy check so a FUTURE PR touching those surfaces is checked
        // pre-merge. Fired once per sweep off `firstNew` (not per-event), behind tessary.predeploy.enabled,
        // fail-soft (logged, never failing the sweep or blocking the cursor).
        registerPreDeployCheck(job.projectId(), signal, firstNew);
    }

    /**
     * Hand this sweep's firings to the arming gate. A classifier with no {@code arming} block in its
     * config does nothing here, which is the shipped default: the platform draws no bar on anyone's
     * behalf.
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
            ClassifierJobRow job, ClassifierRow signal, Grain grain, int scanned, int fired, Instant start) {
        StructuredLog.info(log, Markers.OPS, "signal.sweep.complete")
                .message(
                        "swept %d observation(s) for %s at %s grain, %d fired",
                        scanned, signal.classifierKey(), grain.name().toLowerCase(Locale.ROOT), fired)
                .field("job", job.id())
                .field("signal", signal.classifierKey())
                .field("classifierId", signal.id())
                .field("project", job.projectId())
                .field("grain", grain.name().toLowerCase(Locale.ROOT))
                .field("scanned", scanned)
                .field("fired", fired)
                .durationMs(start)
                .log();
    }

    /**
     * At most one scored observation per turn, keeping the earliest by the window's {@code (created_at,
     * id)} order. The structural turn-root filter already drops inner calls and the {@code agent}/{@code
     * llm} twin (the twin is the root's CHILD), so this only bites when a producer emits several
     * PARENTLESS root spans under one turn — several sequential top-level LLM calls answering one user
     * message. Those are one user utterance, and the classifier must speak once about it.
     *
     * <p>Scope is the window, not all history, and it no longer needs to be more: a turn whose roots
     * straddle a batch boundary used to be scored twice because the verdict's uniqueness was
     * span-scoped. Frustration's detection table keys on the trace, so the cross-batch case is now a
     * conflict rather than a duplicate row, and this filter is left doing the one job it is right for —
     * not scoring the same turn several times inside one batch.
     *
     * <p>An observation with no turn context ({@code turnId == null}) is never folded into another — it
     * keys on its own id, so an unparented span stays its own unit rather than colliding with one.
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
     * Write a fired detection into this classifier's own table (migration {@code 0088}), carrying the
     * producer subject the detection is about — the session it belongs to (null for anonymous traffic),
     * its trace, and its span — plus the detector's severity, confidence band and evidence.
     *
     * <p><b>The turn-grain duplicate is fixed by the table, not by a lookup.</b> This used to write a
     * {@code verdict} whose uniqueness was span-scoped, so a frustration firing under a DIFFERENT
     * parentless root span of the same turn inserted a second row, and the guard against it was a
     * read of every verdict on the trace. Frustration's detection table's unique key is the trace: the
     * turn IS the trace in v2, that is the grain the classifier judges at, and a second root under one
     * turn now conflicts rather than needing to be looked up.
     *
     * <p>Fail-soft: a persistence failure degrades to a logged warning so one bad detection never fails
     * the sweep or blocks the cursor.
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

    private static String shortHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "host";
        }
    }
}
