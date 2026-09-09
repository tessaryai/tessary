// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.config.RcaProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the {@code rca} job queue — the same heartbeat/claim/dispatch shape as {@link
 * ai.tessary.classifier.grader.GraderRunWorker}: fail exhausted jobs, claim due ones ({@code FOR UPDATE
 * SKIP LOCKED}), hand each to the bounded {@code rcaTaskExecutor}, where {@link RcaAnalysisService}
 * runs the pipeline and stamps the report. A failure stamps the report {@code failed} with the error
 * as its summary, so the polling UI always converges (see also {@link RcaReportRepository} on why
 * reads take status from the job row).
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
    private final String leaseOwner =
            shortHost() + "-" + UUID.randomUUID().toString().substring(0, 8);

    public RcaWorker(
            RcaJobRepository jobs,
            RcaReportRepository reports,
            RcaAnalysisService analysis,
            RcaProperties props,
            TraceMdcBridge traceBridge,
            @Qualifier("rcaTaskExecutor") TaskExecutor executor) {
        this.jobs = jobs;
        this.reports = reports;
        this.analysis = analysis;
        this.props = props;
        this.traceBridge = traceBridge;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${tessary.rca.heartbeat-ms:15000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        try {
            int exhausted = jobs.failExhausted(props.getMaxAttempts());
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
                batch = jobs.claimBatch(
                        leaseOwner, props.getBatchSize(), props.getLeaseSeconds(), props.getMaxAttempts());
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

    /** Test seam: run the production handler for one job directly. */
    void runForTest(RcaJobRow job) {
        run(job);
    }

    private void run(RcaJobRow job) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put(LogContext.PROJECT_ID, job.projectId());
        ctx.put(LogContext.JOB_ID, job.id());
        try (LogContext ignored = LogContext.put(ctx)) {
            analysis.analyze(job);
            jobs.markDone(job.id());
        } catch (RuntimeException e) {
            // Log the error CODE, not just the message: it is the one field that separates "the
            // launcher is down" from "the agent returned junk", and it is bounded enough to egress.
            // The message can embed model output (Jackson parse failures echo it), so it and the
            // throwable stay at DEBUG — backend/AGENTS.md § Logging. The infrastructure failures
            // that warrant a stack trace already log one with their cause in E2bRcaSandbox.
            String code = e instanceof TessaryException ee
                    ? ee.error().code()
                    : e.getClass().getSimpleName();
            log.error(
                    Markers.OPS,
                    "rca job failed job={} subject={}:{} code={}",
                    job.id(),
                    job.subjectKind(),
                    job.subjectId(),
                    code);
            log.debug("rca job failed job={} detail", job.id(), e);
            jobs.markFailed(job.id(), e.getMessage(), props.getMaxAttempts());
            try {
                reports.complete(job.id(), RcaJobRow.FAILED, null, e.getMessage(), null, null, null);
            } catch (RuntimeException stampFailure) {
                log.warn(Markers.OPS, "rca failed-report stamp failed job={}: {}", job.id(), stampFailure.getMessage());
            }
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
