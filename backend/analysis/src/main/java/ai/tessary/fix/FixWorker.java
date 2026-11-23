// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.Outcome;
import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.ProviderStop;
import ai.tessary.agentrun.RunSteps;
import ai.tessary.config.FixProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains queued fix runs into {@code fixTaskExecutor}, one {@code agent_run} on the {@code fix} lane each, so the case
 * page can show the run's steps. A launcher at its session cap sends the run back to the queue; any other failure
 * fails the run with its error code. A run left running past twice the session's timeout belongs to a worker that
 * stopped: its session is stopped and the run fails as interrupted.
 */
@Component
public class FixWorker {

    private static final Logger log = LoggerFactory.getLogger(FixWorker.class);

    /** Stops one tick from walking an unbounded queue; the rest waits for the next tick. */
    static final int MAX_CLAIMS = 100;

    /** A claimed run may wait for a free slot behind one full session before its own starts. */
    static final Duration STALE_MARGIN = Duration.ofMinutes(10);

    private final FixRunRepository fixRuns;
    private final FixStep step;
    private final AgentRunRepository runs;
    private final AgentSessionClient sessions;
    private final FixProperties props;
    private final TraceMdcBridge traceBridge;
    private final TaskExecutor executor;
    private final Clock clock;

    @Autowired
    public FixWorker(
            FixRunRepository fixRuns,
            FixStep step,
            AgentRunRepository runs,
            AgentSessionClient sessions,
            FixProperties props,
            TraceMdcBridge traceBridge,
            @Qualifier("fixTaskExecutor") TaskExecutor executor) {
        this(fixRuns, step, runs, sessions, props, traceBridge, executor, Clock.systemUTC());
    }

    FixWorker(
            FixRunRepository fixRuns,
            FixStep step,
            AgentRunRepository runs,
            AgentSessionClient sessions,
            FixProperties props,
            TraceMdcBridge traceBridge,
            TaskExecutor executor,
            Clock clock) {
        this.fixRuns = fixRuns;
        this.step = step;
        this.runs = runs;
        this.sessions = sessions;
        this.props = props;
        this.traceBridge = traceBridge;
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tessary.fix.heartbeat-ms:15000}",
            initialDelayString = "${tessary.fix.heartbeat-ms:15000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            sweep();
            drain();
        }
    }

    private void sweep() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofMillis(props.timeoutMs() * 2)).minus(STALE_MARGIN);
        try {
            for (FixRunRow stale : fixRuns.runningSince(cutoff)) {
                abandon(stale.id());
                fixRuns.fail(stale.id(), FixRunRow.Reason.INTERRUPTED, now);
            }
        } catch (RuntimeException e) {
            log.warn(Markers.OPS, "fix stale sweep failed: {}", e.getMessage());
        }
    }

    /** A session the stopped worker left open may still be spending. */
    private void abandon(String fixRunId) {
        for (AgentRunRepository.RunSummary orphan : runs.openRunsOfJob(fixRunId)) {
            String sessionId = orphan.sessionId();
            if (sessionId != null) sessions.stop(sessionId);
            runs.close(orphan.id(), Outcome.ABANDONED, null);
        }
    }

    private void drain() {
        for (int i = 0; i < MAX_CLAIMS; i++) {
            Optional<FixRunRow> next;
            try {
                next = fixRuns.claimNext(clock.instant());
            } catch (RuntimeException e) {
                log.warn(Markers.OPS, "fix claim failed: {}", e.getMessage());
                return;
            }
            if (next.isEmpty()) return;
            FixRunRow run = next.get();
            executor.execute(() -> run(run));
        }
    }

    void run(FixRunRow run) {
        try (LogContext ignored =
                LogContext.put(Map.of(LogContext.PROJECT_ID, run.projectId(), LogContext.JOB_ID, run.id()))) {
            String agentRunId = runs.open(run.projectId(), AgentRunRepository.Lane.FIX, run.id(), run.id(), null);
            try {
                step.run(run, new RunSteps(runs, agentRunId));
                runs.close(agentRunId, Outcome.DONE, null);
            } catch (TessaryException e) {
                if (AgentRunError.CAPACITY.equals(e.error())) {
                    fixRuns.requeue(run.id(), clock.instant());
                    runs.close(agentRunId, Outcome.RELEASED, e.error().code());
                    return;
                }
                fail(
                        run,
                        agentRunId,
                        e instanceof ProviderStop stop ? stop.kind() : e.error().code());
            } catch (RuntimeException e) {
                fail(run, agentRunId, AgentRunError.RUN_FAILED.code());
            }
        }
    }

    private void fail(FixRunRow run, String agentRunId, String reason) {
        StructuredLog.error(log, Markers.OPS, "fix.run-failed")
                .message("fix run " + run.id() + " failed: " + reason)
                .field("project", run.projectId())
                .field("run", run.id())
                .field("reason", reason)
                .log();
        fixRuns.fail(run.id(), reason, clock.instant());
        runs.close(agentRunId, Outcome.FAILED, reason);
    }
}
