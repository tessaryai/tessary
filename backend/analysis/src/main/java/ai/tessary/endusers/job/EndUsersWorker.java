// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.config.EndUsersProperties;
import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.job.EndUserJobRepository.Kind;
import ai.tessary.model.JobStatus;
import ai.tessary.open.jobqueue.LeasedJobSql;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules and runs the two end-user jobs (plan 3.2). The hourly hit sweep is one job for each project with new
 * evidence; the daily job is one for each active project after 01:00 UTC for the last closed UTC day, and runs its
 * stages in order, storing the last stage done so a retry starts after it. One job runs at a time in this process, so
 * the jobs never hold more than one connection of the pool. A failed job is due again after {@link #RETRY_DELAY} and is
 * dead after {@code max-attempts}. Logs carry the stage, row counts and durations: a stage's exception message can hold
 * a user ID or key, so only its class is logged and stored.
 */
@Component
public class EndUsersWorker {

    private static final Logger log = LoggerFactory.getLogger(EndUsersWorker.class);

    static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    static final Duration DAILY_AFTER = Duration.ofHours(1);

    private final EndUserJobRepository jobs;
    private final EndUsersStage hits;
    private final List<EndUsersStage> daily;
    private final EndUsersProperties props;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();
    private final String owner =
            LeasedJobSql.leaseOwner(() -> InetAddress.getLocalHost().getHostName());

    @Autowired
    public EndUsersWorker(
            EndUserJobRepository jobs,
            EndUsersProperties props,
            HitsStage hits,
            FoldStage fold,
            ReconcileStage reconcile,
            LearnStage learn,
            JudgeStage judge,
            HorizonSweepStage sweep) {
        this(jobs, hits, List.of(fold, reconcile, learn, judge, sweep), props, Clock.systemUTC());
    }

    EndUsersWorker(
            EndUserJobRepository jobs,
            EndUsersStage hits,
            List<EndUsersStage> daily,
            EndUsersProperties props,
            Clock clock) {
        this.jobs = jobs;
        this.hits = hits;
        this.daily = List.copyOf(daily);
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tessary.end-users.hits-heartbeat-ms:300000}",
            initialDelayString = "${tessary.end-users.hits-heartbeat-ms:300000}")
    public void hitsTick() {
        if (!props.collectEnabled()) return;
        Instant now = now();
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        int scheduled = jobs.scheduleHits(hour, EndUserKeys.dayIndex(hour), now);
        logScheduled(Kind.HITS, scheduled);
        drain(Kind.HITS, List.of(hits));
    }

    @Scheduled(
            fixedDelayString = "${tessary.end-users.daily-heartbeat-ms:300000}",
            initialDelayString = "${tessary.end-users.daily-heartbeat-ms:300000}")
    public void dailyTick() {
        if (!props.collectEnabled()) return;
        Instant now = now();
        int today = EndUserKeys.dayIndex(now);
        if (!now.isBefore(Instant.ofEpochSecond(today * 86_400L).plus(DAILY_AFTER))) {
            logScheduled(Kind.DAILY, jobs.scheduleDaily(today - 1, now));
        }
        drain(Kind.DAILY, daily);
    }

    private void drain(Kind kind, List<EndUsersStage> stages) {
        running.lock();
        try {
            int dead = jobs.failExhausted(kind, now(), props.maxAttempts());
            if (dead > 0) {
                StructuredLog.warn(log, Markers.OPS, "endusers.job.exhausted")
                        .message("dead-lettered %d %s jobs whose worker stopped", dead, kind.name())
                        .field("job", kind.name())
                        .field("jobs", dead)
                        .log();
            }
            Optional<EndUserJob> job;
            while ((job = jobs.claim(kind, owner, now(), props.leaseSeconds(), props.maxAttempts())).isPresent()) {
                run(kind, job.get(), stages);
            }
        } finally {
            running.unlock();
        }
    }

    private void run(Kind kind, EndUserJob job, List<EndUsersStage> stages) {
        List<String> names = stages.stream().map(EndUsersStage::name).toList();
        for (EndUsersStage stage : stages.subList(names.indexOf(job.lastStage()) + 1, stages.size())) {
            Instant began = Instant.now();
            long rows;
            try {
                rows = stage.run(job.projectId(), job.day());
            } catch (RuntimeException e) {
                fail(kind, job, stage, e, began);
                return;
            }
            jobs.saveProgress(job.id(), stage.name(), now());
            StructuredLog.info(log, Markers.OPS, "endusers.job.stage")
                    .message("%s stage %s wrote %d rows", kind.name(), stage.name(), rows)
                    .field("project", job.projectId())
                    .field("job", kind.name())
                    .field("day", job.day())
                    .field("stage", stage.name())
                    .field("rows", rows)
                    .durationMs(began)
                    .log();
        }
        jobs.markDone(job.id(), now());
    }

    private void fail(Kind kind, EndUserJob job, EndUsersStage stage, RuntimeException e, Instant began) {
        String error = e.getClass().getName();
        Instant now = now();
        String status =
                jobs.markFailed(job.id(), stage.name() + ": " + error, now.plus(RETRY_DELAY), now, props.maxAttempts());
        StructuredLog.warn(log, Markers.OPS, "endusers.job.stage_failed")
                .message(
                        "%s stage %s failed with %s on attempt %d of %d; job is %s",
                        kind.name(), stage.name(), error, job.attempts(), props.maxAttempts(), status)
                .field("project", job.projectId())
                .field("job", kind.name())
                .field("day", job.day())
                .field("stage", stage.name())
                .field("error", error)
                .field("attempt", job.attempts())
                .field("dead", JobStatus.DEAD.equals(status))
                .durationMs(began)
                .log();
    }

    private void logScheduled(Kind kind, int scheduled) {
        if (scheduled == 0) return;
        StructuredLog.info(log, Markers.OPS, "endusers.job.scheduled")
                .message("scheduled %d %s jobs", scheduled, kind.name())
                .field("job", kind.name())
                .field("jobs", scheduled)
                .log();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
