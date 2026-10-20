// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.Outcome;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.ingest.RetryPolicy;
import ai.tessary.open.errors.AwaitsConfiguration;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.Retryable;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Never re-pins a baseline or writes the allowlist; it only decides whether a person sees the finding. A run that did
 * not happen writes nothing: the engine throws and the job retries, so an infra blip cannot silently close a real
 * regression.
 */
@Component
public class BehaviorTriageWorker {

    private static final Logger log = LoggerFactory.getLogger(BehaviorTriageWorker.class);

    private static final int CLAIM_BATCH = 3;

    /** Only the schedule is used; the queue's {@code maxAttempts} owns the cap. */
    private static final RetryPolicy RETRY_BACKOFF = RetryPolicy.DEFAULT;

    private static final int MAX_CLAIM_ROUNDS = 100;

    private static final long LEASE_HEADROOM_SECONDS = 300;

    private final BehaviorTriageJobRepository jobs;
    /**
     * This worker owns everything around the run (lease, backoff, breaker, logs) so stores cannot dead-letter
     * differently.
     */
    private final List<TriageSource> sources;

    private final BehaviorTriageEngine engine;
    private final ClassifierProperties props;
    private final ObserverProperties observerProps;
    private final TaskExecutor executor;
    private final TriageLauncherBreaker breaker;

    /** One run per attempt, closed with how it ended, so the finding page can show where triage stands. */
    private final AgentRunRepository runs;

    /** Stops the session a crashed attempt left open, so it cannot keep spending. */
    private final AgentSessionClient sessions;

    private final TraceMdcBridge traceBridge;
    private final String leaseOwner =
            "behavior-triage-" + UUID.randomUUID().toString().substring(0, 8);

    public BehaviorTriageWorker(
            BehaviorTriageJobRepository jobs,
            List<TriageSource> sources,
            BehaviorTriageEngine engine,
            ClassifierProperties props,
            ObserverProperties observerProps,
            TraceMdcBridge traceBridge,
            @Qualifier("behaviorTriageTaskExecutor") TaskExecutor executor,
            TriageLauncherBreaker breaker,
            AgentRunRepository runs,
            AgentSessionClient sessions) {
        this.jobs = jobs;
        this.sources = sources;
        this.engine = engine;
        this.props = props;
        this.observerProps = observerProps;
        this.traceBridge = traceBridge;
        this.executor = executor;
        this.breaker = breaker;
        this.runs = runs;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${tessary.classifier.behavior-triage-ms:15000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        // Checked before the dead-letter sweep too: while the launcher is shut, leases keep expiring and the sweep
        // would dead-letter the whole queue.
        if (breaker.isOpen()) {
            StructuredLog.info(log, Markers.OPS, "triage.drain.parked")
                    .message("the triage launcher is down; skipping this drain")
                    .field("resumes_in_seconds", breaker.secondsRemaining())
                    .log();
            return;
        }
        try {
            int exhausted = jobs.failExhausted(props.maxAttempts());
            if (exhausted > 0) {
                StructuredLog.error(log, Markers.OPS, "behavior.triage.dead-lettered")
                        .field("count", exhausted)
                        .log();
            }
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "behavior.triage.sweep-failed")
                    .field("error", e.getMessage())
                    .log();
            return;
        }
        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            List<BehaviorTriageJobRow> batch;
            try {
                batch = jobs.claimBatch(leaseOwner, CLAIM_BATCH, leaseSeconds(), props.maxAttempts());
            } catch (RuntimeException e) {
                StructuredLog.warn(log, Markers.OPS, "behavior.triage.claim-failed")
                        .field("error", e.getMessage())
                        .log();
                return;
            }
            if (batch.isEmpty()) return;
            for (BehaviorTriageJobRow job : batch) {
                executor.execute(() -> run(job));
            }
            // Jobs already dispatched release themselves without spending an attempt.
            if (breaker.isOpen()) return;
        }
    }

    /**
     * Must outlast the agentic run (30 minutes), not the 300-second sweep lease, or a run is re-claimed mid-flight and
     * a second microVM spawns for the same finding.
     */
    private long leaseSeconds() {
        long agentSeconds = observerProps.getAgentic().getTimeoutMs() / 1000L;
        return Math.max(observerProps.getLeaseSeconds(), agentSeconds + LEASE_HEADROOM_SECONDS);
    }

    void triageForTest(BehaviorTriageJobRow job) {
        run(job);
    }

    private void run(BehaviorTriageJobRow job) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put(LogContext.PROJECT_ID, job.projectId());
        ctx.put(LogContext.JOB_ID, job.id());
        String runId = null;
        try (LogContext ignored = LogContext.put(ctx)) {
            abandonOrphans(job);
            TriageSource owner = null;
            TriageBrief brief = null;
            for (TriageSource source : sources) {
                Optional<TriageBrief> candidate = source.brief(job);
                if (candidate.isPresent()) {
                    owner = source;
                    brief = candidate.get();
                    break;
                }
            }
            if (owner == null || brief == null) {
                // The finding was resolved while the job waited: done, not failed.
                jobs.markDone(job.id());
                return;
            }
            runId = runs.open(
                    job.projectId(), AgentRunRepository.Lane.TRIAGE, job.findingId(), job.id(), job.attempts());
            BehaviorTriageVerdict verdict = engine.rule(job.projectId(), job.findingId(), brief, runId);
            owner.recordVerdict(
                    job.projectId(),
                    job.findingId(),
                    verdict,
                    engine.citationsJson(verdict),
                    Instant.now().toString());
            StructuredLog.info(log, Markers.OPS, "behavior.triage.recorded")
                    .message("triaged " + job.findingId() + ": " + verdict.verdict() + " -> " + verdict.action())
                    .field("project", job.projectId())
                    .field("finding", job.findingId())
                    .field("kind", owner.kind())
                    .field("verdict", verdict.verdict())
                    .field("action", verdict.action())
                    .field("citations", verdict.citations().size())
                    .log();
            jobs.markDone(job.id());
            close(runId, Outcome.DONE, null);
            breaker.recordReachable();
        } catch (TessaryException e) {
            if (ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE.equals(e.error())) {
                // DEBUG because the breaker carries the one line an operator needs.
                log.debug("triage of {} released unspent: {}", job.findingId(), e.getMessage());
                jobs.releaseWithoutAttempt(job.id(), e.getMessage());
                close(runId, Outcome.RELEASED, e.error().code());
                breaker.recordLauncherFailure(e.toString());
                return;
            }
            if (ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED.equals(e.error())) {
                // Parked, not retried: it won't change until a person edits the deployment. WARN because no breaker
                // line carries it.
                releaseUntilConfigured(job, runId, e, "the triage launcher is misconfigured: " + e.getMessage());
                return;
            }
            if (isCredentialGap(e)) {
                releaseUntilConfigured(job, runId, e, "the org has no usable provider credential");
                return;
            }
            if (isProviderRefusal(e)) {
                releaseUntilConfigured(job, runId, e, "the model provider refuses the run: " + e.getMessage());
                return;
            }
            if (e instanceof AwaitsConfiguration) {
                releaseUntilConfigured(job, runId, e, "someone acts on: " + e.getMessage());
                return;
            }
            if (ClassifierError.TRIAGE_SANDBOX_BUSY.equals(e.error()) && e instanceof Retryable busy) {
                // No error kept: nothing failed, so the finding still reads Queued rather than Retrying.
                log.debug("triage of {} waits for a free sandbox: {}", job.findingId(), e.getMessage());
                jobs.releaseWithoutAttempt(job.id(), null, busy.retryAfterSeconds());
                close(runId, Outcome.RELEASED, e.error().code());
                return;
            }
            if (e instanceof Retryable retryable) {
                // Clears on its own; back after the delay it names, unspent.
                long delaySeconds = retryable.retryAfterSeconds();
                log.debug("triage of {} deferred {}s: {}", job.findingId(), delaySeconds, e.getMessage());
                jobs.releaseWithoutAttempt(job.id(), e.getMessage(), delaySeconds);
                close(runId, Outcome.RELEASED, e.error().code());
                return;
            }
            retry(job, runId, e, e.error().code());
        } catch (RuntimeException e) {
            retry(job, runId, e, ClassifierError.TRIAGE_RUN_INCOMPLETE.code());
        }
    }

    /**
     * A run still open on a job just claimed belongs to an attempt whose worker died: its session may still be
     * spending, so it is stopped and the run marked abandoned before this attempt opens its own. A launcher that cannot
     * take the stop fails this attempt the way the run itself would.
     */
    private void abandonOrphans(BehaviorTriageJobRow job) {
        for (AgentRunRepository.RunSummary orphan : runs.openRunsOfJob(job.id())) {
            String sessionId = orphan.sessionId();
            if (sessionId != null) sessions.stop(sessionId);
            runs.close(orphan.id(), Outcome.ABANDONED, null);
        }
    }

    /** Never masks how the job ended: a run that cannot be closed only leaves the page a step behind. */
    private void close(@Nullable String runId, Outcome outcome, @Nullable String stopCode) {
        if (runId == null) return;
        try {
            runs.close(runId, outcome, stopCode);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "behavior.triage.run-close-failed")
                    .message("could not close triage run " + runId + " as " + outcome.wire())
                    .field("run", runId)
                    .field("outcome", outcome.wire())
                    .field("error", e.toString())
                    .log();
        }
    }

    /**
     * Not a launcher fault, so it does not feed {@link TriageLauncherBreaker}: one credential-less org would park the
     * drain for every org.
     */
    private static boolean isCredentialGap(TessaryException e) {
        return ModelConfigError.MISSING_CREDENTIALS.equals(e.error())
                || ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED.equals(e.error());
    }

    /** Same reasoning as {@link #isCredentialGap}: the org's own key or model, so no other org's drain parks. */
    private static boolean isProviderRefusal(TessaryException e) {
        return ClassifierError.TRIAGE_PROVIDER_REJECTED.equals(e.error())
                || ClassifierError.TRIAGE_REQUEST_REFUSED.equals(e.error());
    }

    /**
     * The failure is deterministic and instant, so backoff would burn every attempt in thirty seconds and dead-letter a
     * finding nothing can reach again. WARN because it names an action a person must take.
     */
    private void releaseUntilConfigured(
            BehaviorTriageJobRow job, @Nullable String runId, TessaryException e, String because) {
        long retrySeconds = props.triageConfigRetrySeconds();
        StructuredLog.warn(log, Markers.OPS, "behavior.triage.awaiting-configuration")
                .message("triage of " + job.findingId() + " cannot run until " + because + "; re-checking in "
                        + retrySeconds + "s")
                .field("project", job.projectId())
                .field("finding", job.findingId())
                .field("error_code", e.error().code())
                .field("retry_in_seconds", retrySeconds)
                .log();
        jobs.holdUntilConfigured(job.id(), e.getMessage(), e.error().code(), retrySeconds);
        close(runId, Outcome.HELD, e.error().code());
    }

    /**
     * Never terminal on first failure, which would strand the finding un-triaged and un-retryable. The delay stops a
     * deterministic failure spending every attempt in a minute.
     */
    private void retry(BehaviorTriageJobRow job, @Nullable String runId, RuntimeException e, String code) {
        long delaySeconds = RETRY_BACKOFF.backoffMs(job.attempts(), 0L) / 1000L;
        StructuredLog.warn(log, Markers.OPS, "behavior.triage.run-failed")
                .message("triage of " + job.findingId() + " produced no ruling; retrying in " + delaySeconds + "s")
                .field("project", job.projectId())
                .field("finding", job.findingId())
                .field("attempts", job.attempts())
                .field("retry_in_seconds", delaySeconds)
                .field("error", e.getMessage())
                .log();
        jobs.markRetryable(job.id(), e.getMessage(), delaySeconds);
        close(runId, job.attempts() >= props.maxAttempts() ? Outcome.DEAD : Outcome.RETRYING, code);
    }
}
