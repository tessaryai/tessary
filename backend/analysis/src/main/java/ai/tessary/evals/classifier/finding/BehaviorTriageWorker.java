// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import ai.tessary.evals.config.ClassifierProperties;
import ai.tessary.evals.config.ObserverProperties;
import ai.tessary.evals.config.TraceMdcBridge;
import ai.tessary.evals.ingest.RetryPolicy;
import ai.tessary.evals.open.errors.ClassifierError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.ModelConfigError;
import ai.tessary.evals.open.obs.LogContext;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the triage queue ({@code job.kind='triage'}): for each claimed finding, run
 * {@link BehaviorTriageEngine} and record its ruling on the finding.
 *
 * <p>Recording the ruling is the only effect on detector state: the worker still does not re-pin a
 * baseline, write the allowlist or touch gram state, because absorbing a shift moves the reference a
 * whole population is compared against. What it DOES decide is whether a person ever sees this finding
 * — {@code positive} opens a case, {@code negative} and {@code unclear} close it — and that is the
 * autonomy the lane was rebuilt for.
 *
 * <p><b>A run that did not happen writes nothing.</b> The engine throws rather than degrading to a
 * verdict, and this worker turns that into a retryable failure: the lease is expired, the next tick
 * re-claims while attempts remain, and {@code failExhausted} dead-letters it. That is what makes
 * "unclear closes the finding" safe — an infra blip cannot silently close a real regression.
 *
 * <p>Runs on a slower cadence than the sweep: the queue is bounded by distinct causes rather than by
 * traffic, and nothing downstream is waiting on the answer.
 *
 * <p>The one thing it has to know about the run is how long to lease for — see {@link #leaseSeconds},
 * which is sized against the agent's own wall clock rather than the sweep's, because a lease that
 * expires mid-run lets the job be re-claimed and spawns a second microVM for the same finding.
 */
@Component
public class BehaviorTriageWorker {

    private static final Logger log = LoggerFactory.getLogger(BehaviorTriageWorker.class);

    private static final int CLAIM_BATCH = 3;

    /**
     * Backoff between attempts on a run-level failure. {@code RetryPolicy.DEFAULT}'s own maxAttempts is
     * ignored here — the queue's {@code maxAttempts} owns that — and only its schedule is used:
     * 2s, 4s, 8s, 16s, capped at 60s.
     */
    private static final RetryPolicy RETRY_BACKOFF = RetryPolicy.DEFAULT;

    private static final int MAX_CLAIM_ROUNDS = 100;

    /** Slack over the agent's own timeout, covering sandbox teardown after the agent returns. */
    private static final long LEASE_HEADROOM_SECONDS = 300;

    private final BehaviorTriageJobRepository jobs;
    /**
     * The finding stores, in registration order. Each one renders its own brief and records its own
     * ruling; this worker owns everything AROUND the run — the lease, the retry backoff, the launcher
     * breaker and the ops log — because duplicating those per store is how two stores start
     * dead-lettering differently.
     */
    private final List<TriageSource> sources;

    private final BehaviorTriageEngine engine;
    private final ClassifierProperties props;
    private final ObserverProperties observerProps;
    private final TaskExecutor executor;
    private final TriageLauncherBreaker breaker;

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
            TriageLauncherBreaker breaker) {
        this.jobs = jobs;
        this.sources = sources;
        this.engine = engine;
        this.props = props;
        this.observerProps = observerProps;
        this.traceBridge = traceBridge;
        this.executor = executor;
        this.breaker = breaker;
    }

    @Scheduled(fixedDelayString = "${evals.classifier.behavior-triage-ms:300000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        // Checked before the dead-letter sweep as well as the drain: while the launcher is shut, every
        // job's lease keeps expiring, and sweeping would dead-letter the whole queue for a reason that
        // was never about any of it.
        if (breaker.isOpen()) {
            StructuredLog.info(log, Markers.OPS, "triage.drain.parked")
                    .message("the triage launcher is down; skipping this drain")
                    .field("resumes_in_seconds", breaker.secondsRemaining())
                    .log();
            return;
        }
        try {
            int exhausted = jobs.failExhausted(props.getMaxAttempts());
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
                batch = jobs.claimBatch(leaseOwner, CLAIM_BATCH, leaseSeconds(), props.getMaxAttempts());
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
            // A trip mid-tick stops the remaining rounds; the jobs already dispatched will release
            // themselves without spending an attempt.
            if (breaker.isOpen()) return;
        }
    }

    /**
     * The lease must outlast the agentic run, not the DB-bound sweep.
     *
     * <p>{@code ClassifierProperties.leaseSeconds} is 300 — sized for the signal sweep. The agent gets
     * {@code ObserverProperties.Agentic.timeoutMs} (20 minutes). Leasing for the shorter of the two
     * meant {@code LeasedJobSql} re-claimed any run over 5 minutes and spawned a SECOND microVM for
     * the same finding while the first was still in flight, up to {@code maxAttempts} — multiplying
     * exactly the slowest, most expensive analyses and defeating the recurrence gate's whole purpose.
     * {@code ObserverProperties.leaseSeconds} is the lease that field was written against.
     */
    private long leaseSeconds() {
        long agentSeconds = observerProps.getAgentic().getTimeoutMs() / 1000L;
        return Math.max(observerProps.getLeaseSeconds(), agentSeconds + LEASE_HEADROOM_SECONDS);
    }

    /** Test seam: triage one claimed job directly, without the scheduler or the executor. */
    void triageForTest(BehaviorTriageJobRow job) {
        run(job);
    }

    private void run(BehaviorTriageJobRow job) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put(LogContext.PROJECT_ID, job.projectId());
        ctx.put(LogContext.JOB_ID, job.id());
        try (LogContext ignored = LogContext.put(ctx)) {
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
                // No source claimed the job: the finding was resolved, or its epoch was closed, while the
                // job waited. Nothing to rule on and nowhere to write the answer — done, not failed. This
                // is the same path the pre-seam worker took on the same condition, in both of its two
                // branches, so the seam adds no new state and no new error code.
                jobs.markDone(job.id());
                return;
            }
            BehaviorTriageVerdict verdict =
                    engine.rule(job.projectId(), job.findingId(), brief.dossier(), brief.prompt(), brief.claimJson());
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
            breaker.recordReachable();
        } catch (EvalsException e) {
            if (ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE.equals(e.error())) {
                // Not this finding's failure. Give the attempt back and let the breaker decide whether the
                // drain should stop — logged at DEBUG because the breaker itself carries the one line an
                // operator needs, and this would otherwise be the per-job noise the breaker exists to end.
                log.debug("triage of {} released unspent: {}", job.findingId(), e.getMessage());
                jobs.releaseWithoutAttempt(job.id(), e.getMessage());
                breaker.recordLauncherFailure(e.toString());
                return;
            }
            if (ClassifierError.TRIAGE_LAUNCHER_MISCONFIGURED.equals(e.error())) {
                // Parked like a credential gap, not retried like an outage, and for the same reason:
                // the answer will not change until a person edits the deployment. The attempt is
                // still not spent — a fixed key must resume this finding, not find it dead-lettered
                // over a mistake that was never about it — but unlike the unavailable branch above
                // it says so at WARN, because no breaker line carries this one and the message names
                // the variable to fix.
                releaseUntilConfigured(job, e, "the triage launcher is misconfigured: " + e.getMessage());
                return;
            }
            if (isCredentialGap(e)) {
                releaseUntilConfigured(job, e, "the org has no usable provider credential");
                return;
            }
            retry(job, e);
        } catch (RuntimeException e) {
            retry(job, e);
        }
    }

    /**
     * Whether this failure is the org having no usable credential for the lane — thrown by
     * {@code AgenticCredentialResolver} inside {@code E2bTriageSandbox}, BEFORE the launcher is called.
     *
     * <p>Both codes mean the same operationally: nothing about this finding, this launcher or this
     * attempt will change the outcome until a person edits Settings → Providers. {@code MISSING_CREDENTIALS}
     * is "the org has no key for the provider this lane resolved to"; {@code AGENTIC_IAM_ROLE_UNSUPPORTED}
     * is "it has one and a sandbox cannot use it". Neither is a launcher fault, which is why this does not
     * feed {@link TriageLauncherBreaker}: one credential-less org would otherwise park the drain for every
     * other org on the box.
     */
    private static boolean isCredentialGap(EvalsException e) {
        return ModelConfigError.MISSING_CREDENTIALS.equals(e.error())
                || ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED.equals(e.error());
    }

    /**
     * Park the job on the org's configuration without spending an attempt, and re-check it a window later.
     *
     * <p>This is the bug that made a credential gap permanent. The failure is deterministic and instant —
     * it happens before any network call — so with {@code retry}'s 2/4/8/16s backoff all five attempts
     * burned inside about thirty seconds of the first sweep that escalated the finding, the job
     * dead-lettered, and nothing could reach it again: the escalation sweep skips a finding with
     * {@code escalated_at} set, the recurrence re-open needs a {@code closed} ruling this finding never
     * got, and adding the credential later published no event. The finding stayed un-triaged for good,
     * for a reason that was never about it.
     *
     * <p>WARN rather than DEBUG, unlike the launcher release beside it: there is no breaker line carrying
     * this one, and it names an action a person has to take.
     */
    private void releaseUntilConfigured(BehaviorTriageJobRow job, EvalsException e, String because) {
        long retrySeconds = props.getTriageConfigRetrySeconds();
        StructuredLog.warn(log, Markers.OPS, "behavior.triage.awaiting-configuration")
                .message("triage of " + job.findingId() + " cannot run until " + because + "; re-checking in "
                        + retrySeconds + "s")
                .field("project", job.projectId())
                .field("finding", job.findingId())
                .field("error_code", e.error().code())
                .field("retry_in_seconds", retrySeconds)
                .log();
        jobs.releaseWithoutAttempt(job.id(), e.getMessage(), retrySeconds);
    }

    /**
     * Retryable, never terminal-on-first-failure: the finding keeps a NULL verdict and the job goes back
     * to the queue with its attempt spent. Marking it failed here would strand the finding un-triaged AND
     * un-retryable, which is the shape of the bug this milestone removed.
     *
     * <p>The delay is what stops a deterministic failure spending every attempt inside one minute.
     */
    private void retry(BehaviorTriageJobRow job, RuntimeException e) {
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
    }
}
