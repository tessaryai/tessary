// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.Outcome;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.Retryable;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.jobqueue.LeasedJobSql;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the {@code rca} queue into {@code rcaTaskExecutor}. A failure stamps the report {@code failed} so the polling
 * UI always converges; nothing retries or holds, since a person pressed Run RCA and re-runs it. The one exception is a
 * launcher at its session cap, which sends the job back to the queue unspent. Each run is recorded with how it ended.
 */
@Component
public class RcaWorker {

    private static final Logger log = LoggerFactory.getLogger(RcaWorker.class);

    private static final int MAX_CLAIM_ROUNDS = 10_000;

    private final RcaJobRepository jobs;
    private final RcaReportRepository reports;
    private final RcaAnalysisService analysis;
    private final RcaProperties props;
    private final TaskExecutor executor;
    private final TraceMdcBridge traceBridge;
    private final FindingRepository findings;

    private final CaseEventRepository caseEvents;

    /** One run per attempt, closed with how it ended, so the case page can show where the RCA stands. */
    private final AgentRunRepository runs;

    /** Stops the session a crashed attempt left open, so it cannot keep spending. */
    private final AgentSessionClient sessions;

    private final String leaseOwner =
            LeasedJobSql.leaseOwner(() -> InetAddress.getLocalHost().getHostName());

    public RcaWorker(
            RcaJobRepository jobs,
            RcaReportRepository reports,
            RcaAnalysisService analysis,
            RcaProperties props,
            TraceMdcBridge traceBridge,
            FindingRepository findings,
            CaseEventRepository caseEvents,
            @Qualifier("rcaTaskExecutor") TaskExecutor executor,
            AgentRunRepository runs,
            AgentSessionClient sessions) {
        this.jobs = jobs;
        this.reports = reports;
        this.analysis = analysis;
        this.props = props;
        this.traceBridge = traceBridge;
        this.findings = findings;
        this.caseEvents = caseEvents;
        this.executor = executor;
        this.runs = runs;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${tessary.rca.heartbeat-ms:15000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        try {
            int exhausted = jobs.failExhausted(props.maxAttempts());
            if (exhausted > 0) {
                log.warn(Markers.OPS, "rca failed {} job(s) over the attempt cap", exhausted);
            }
        } catch (RuntimeException e) {
            log.warn(Markers.OPS, "rca exhaustion sweep failed: {}", e.getMessage());
        }

        int dispatched = 0;
        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            List<RcaJobRow> batch;
            try {
                batch = jobs.claimBatch(leaseOwner, props.batchSize(), props.leaseSeconds(), props.maxAttempts());
            } catch (RuntimeException e) {
                log.warn(Markers.OPS, "rca claim failed: {}", e.getMessage());
                break;
            }
            if (batch.isEmpty()) break;
            for (RcaJobRow job : batch) {
                executor.execute(() -> run(job));
                dispatched++;
            }
        }
        if (dispatched > 0) {
            log.info(Markers.OPS, "rca tick dispatched={}", dispatched);
        }
    }

    void run(RcaJobRow job) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put(LogContext.PROJECT_ID, job.projectId());
        ctx.put(LogContext.JOB_ID, job.id());
        String runId = null;
        try (LogContext ignored = LogContext.put(ctx)) {
            abandonOrphans(job);
            runId = reports.findByJobId(job.projectId(), job.id())
                    .map(report -> runs.open(job.projectId(), AgentRunRepository.Lane.RCA, report.id(), job.id(), null))
                    .orElse(null);
            analysis.analyze(job);
            jobs.markDone(job.id());
            close(runId, Outcome.DONE, null);
            appendRcaCompleted(job);
        } catch (TessaryException e) {
            if (RcaError.SANDBOX_BUSY.equals(e.error()) && e instanceof Retryable busy) {
                // Nothing failed and the report stays unstamped, so the case still reads Queued.
                log.debug("rca job {} waits for a free sandbox: {}", job.id(), e.getMessage());
                jobs.requeue(job.id(), busy.retryAfterSeconds());
                close(runId, Outcome.RELEASED, e.error().code());
                return;
            }
            fail(job, runId, e, e.error().code());
        } catch (RuntimeException e) {
            fail(job, runId, e, RcaError.UPSTREAM_FAILED.code());
        }
    }

    private void fail(RcaJobRow job, @Nullable String runId, RuntimeException e, String code) {
        // Log the error CODE, which separates "launcher down" from "agent returned junk"; the message can embed model
        // output, so it stays at DEBUG.
        log.error(
                Markers.OPS,
                "rca job failed job={} subject={}:{} code={}",
                job.id(),
                job.subjectKind(),
                job.subjectId(),
                code);
        log.debug("rca job failed job={} detail", job.id(), e);
        // The job's status is what stops the case page polling, so it changes last; a poll in between would stop
        // reading with the report still pending.
        try {
            reports.complete(job.id(), RcaJobRow.FAILED, null, e.getMessage(), null, null, null, null, null);
        } catch (RuntimeException stampFailure) {
            log.warn(Markers.OPS, "rca failed-report stamp failed job={}: {}", job.id(), stampFailure.getMessage());
        }
        close(runId, Outcome.FAILED, code);
        jobs.markFailed(job.id(), e.getMessage());
    }

    /**
     * A run still open on a job just claimed belongs to an attempt whose worker died: its session may still be
     * spending, so it is stopped and the run marked abandoned before this attempt opens its own. A launcher that cannot
     * take the stop has lost the session already; if it is down, this attempt's own run says so a moment later.
     */
    private void abandonOrphans(RcaJobRow job) {
        for (AgentRunRepository.RunSummary orphan : runs.openRunsOfJob(job.id())) {
            String sessionId = orphan.sessionId();
            if (sessionId != null) stopOrphan(orphan.id(), sessionId);
            runs.close(orphan.id(), Outcome.ABANDONED, null);
        }
    }

    private void stopOrphan(String runId, String sessionId) {
        try {
            sessions.stop(sessionId);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "rca.orphan-stop-failed")
                    .message("could not stop session " + sessionId + " of abandoned rca run " + runId)
                    .field("run", runId)
                    .field("session", sessionId)
                    .field("error", e.toString())
                    .log();
        }
    }

    /** Never masks how the job ended: a run that cannot be closed only leaves the page a step behind. */
    private void close(@Nullable String runId, Outcome outcome, @Nullable String stopCode) {
        if (runId == null) return;
        try {
            runs.close(runId, outcome, stopCode);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "rca.run-close-failed")
                    .message("could not close rca run " + runId + " as " + outcome.wire())
                    .field("run", runId)
                    .field("outcome", outcome.wire())
                    .field("error", e.toString())
                    .log();
        }
    }

    /** Never rethrown: the run finished and {@code markDone} has committed, so a failure costs one trail line. */
    private void appendRcaCompleted(RcaJobRow job) {
        try {
            findings.findById(job.projectId(), job.findingId())
                    .map(FindingRow::caseId)
                    .ifPresent(caseId -> caseEvents.append(
                            job.projectId(),
                            caseId,
                            CaseEventRow.Kind.RCA_COMPLETED,
                            null,
                            "Finished — see the report.",
                            null,
                            Instant.now()));
        } catch (RuntimeException e) {
            log.warn(Markers.OPS, "rca completed trail line failed job={}: {}", job.id(), e.getMessage());
        }
    }
}
