// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.EndUsersProperties;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The two end-user jobs on the {@code job} table, with stub stages. Scheduling and claiming cover the whole install, so
 * each test removes the end-user jobs of other projects before it ticks and asserts on its own project only.
 */
@SpringBootTest
record EndUsersWorkerTest(
        TenantService tenants,
        JdbcClient jdbc,
        EndUserJobRepository jobs,
        EndUserProjectRepository projects,
        EndUsersProperties props) {

    private static final Instant DAY_START = Instant.parse("2026-06-15T00:00:00Z");
    private static final int YESTERDAY = 20618;
    private static final String SECRET_ID = "usr_secret_42";

    @Test
    void dailyJob_scheduledOncePerProjectDay_after0100Utc() {
        Project p = TenantFixture.bootstrap(tenants, "eu-job-daily-once").project();
        MovingClock clock = new MovingClock(DAY_START.plus(Duration.ofMinutes(30)));
        Stub fold = new Stub("fold");
        EndUsersWorker worker = worker(clock, props, new Stub("hits"), fold);

        isolate(p);
        worker.dailyTick();
        int beforeOne = dailyJobs(p).size();
        clock.set(DAY_START.plus(Duration.ofMinutes(65)));
        isolate(p);
        worker.dailyTick();
        clock.set(DAY_START.plus(Duration.ofMinutes(70)));
        isolate(p);
        worker.dailyTick();

        assertAll(
                () -> assertEquals(0, beforeOne, "no job before 01:00 UTC"),
                () -> assertEquals(List.of(p.id() + ":" + YESTERDAY + "|" + YESTERDAY + "|done"), dailyJobs(p)),
                () -> assertEquals(List.of(YESTERDAY), fold.days(p)));
    }

    @Test
    void retry_resumesAtStageAfterProgress() {
        Project p = TenantFixture.bootstrap(tenants, "eu-job-resume").project();
        MovingClock clock = new MovingClock(DAY_START.plus(Duration.ofMinutes(65)));
        Stub fold = new Stub("fold");
        Stub reconcile = new Stub("reconcile");
        Stub learn = new Stub("learn", attempt -> attempt == 1);
        Stub sweep = new Stub("sweep");
        EndUsersWorker worker = worker(clock, props, new Stub("hits"), fold, reconcile, learn, sweep);

        isolate(p);
        worker.dailyTick();
        String afterFailure = jobState(p);
        clock.set(clock.instant().plus(Duration.ofMinutes(10)));
        isolate(p);
        worker.dailyTick();

        assertAll(
                () -> assertEquals("claimed|1|reconcile", afterFailure, "progress names the last stage done"),
                () -> assertEquals("done|2|sweep", jobState(p)),
                () -> assertEquals(1, fold.calls(p), "a retry does not fold again"),
                () -> assertEquals(1, reconcile.calls(p)),
                () -> assertEquals(2, learn.calls(p)),
                () -> assertEquals(1, sweep.calls(p)));
    }

    @Test
    void hitSweep_onlyForProjectsWithNewEvidence() {
        Project fresh = TenantFixture.bootstrap(tenants, "eu-job-hits-fresh").project();
        Project swept = TenantFixture.bootstrap(tenants, "eu-job-hits-swept").project();
        Project firstRun = TenantFixture.bootstrap(tenants, "eu-job-hits-first").project();
        Project quiet = TenantFixture.bootstrap(tenants, "eu-job-hits-quiet").project();
        Instant now = Instant.parse("2026-06-15T03:20:00Z");
        projects.setHitsWatermark(fresh.id(), now.minus(Duration.ofHours(2)));
        evidence(fresh, "2026-06-15T02:10:00Z");
        projects.setHitsWatermark(swept.id(), now.minus(Duration.ofMinutes(10)));
        evidence(swept, "2026-06-15T02:10:00Z");
        evidence(firstRun, "2026-01-01T00:00:00Z");
        projects.setHitsWatermark(quiet.id(), now.minus(Duration.ofHours(2)));
        Stub hits = new Stub("hits");
        EndUsersWorker worker = worker(new MovingClock(now), props, hits);

        worker.hitsTick();

        assertAll(
                () -> assertEquals(List.of(fresh.id() + ":2026-06-15T03:00:00Z|20619|done"), hitJobs(fresh)),
                () -> assertEquals(List.of(), hitJobs(swept), "its evidence is older than the watermark"),
                () -> assertEquals(1, hitJobs(firstRun).size(), "no watermark: the first sweep backfills"),
                () -> assertEquals(List.of(), hitJobs(quiet)),
                () -> assertEquals(1, hits.calls(fresh)),
                () -> assertEquals(0, hits.calls(swept)));
    }

    @Test
    void collectDisabled_schedulesNothing() {
        Project p = TenantFixture.bootstrap(tenants, "eu-job-collect-off").project();
        evidence(p, "2026-06-15T02:10:00Z");
        seedJob(p, "end_users", p.id() + ":" + (YESTERDAY - 1), "pending", 0, null);
        Stub hits = new Stub("hits");
        Stub fold = new Stub("fold");
        EndUsersProperties off = new EndUsersProperties(
                false,
                props.historyDays(),
                props.judgeWindowDays(),
                props.maxIdDays(),
                props.leaseSeconds(),
                props.maxAttempts(),
                props.guards());
        EndUsersWorker worker = worker(new MovingClock(Instant.parse("2026-06-15T03:20:00Z")), off, hits, fold);

        isolate(p);
        worker.dailyTick();
        worker.hitsTick();

        assertAll(
                () -> assertEquals(List.of(), hitJobs(p)),
                () -> assertEquals(
                        List.of(p.id() + ":" + (YESTERDAY - 1) + "|" + (YESTERDAY - 1) + "|pending"), dailyJobs(p)),
                () -> assertEquals(0, fold.calls(p) + hits.calls(p), "a queued job is not run either"));
    }

    @Test
    void stageFailure_marksJobForRetry_andLogsStageRowsAndDuration_withoutUserIds() {
        Project p = TenantFixture.bootstrap(tenants, "eu-job-stage-failure").project();
        Instant now = DAY_START.plus(Duration.ofMinutes(65));
        Stub fold = new Stub("fold");
        Stub reconcile = new Stub("reconcile", attempt -> true);
        EndUsersWorker worker = worker(new MovingClock(now), props, new Stub("hits"), fold, reconcile);

        isolate(p);
        List<ILoggingEvent> events = capture(worker::dailyTick);

        Map<String, Object> done = fields(single(events, "endusers.job.stage", p));
        Map<String, Object> failed = fields(single(events, "endusers.job.stage_failed", p));
        String lastError = jdbc.sql("SELECT last_error FROM job WHERE kind = 'end_users' AND project_id = :pid")
                .param("pid", p.id())
                .query(String.class)
                .single();
        assertAll(
                () -> assertEquals("claimed|1|fold", jobState(p)),
                () -> assertEquals(now.plus(EndUsersWorker.RETRY_DELAY).toString(), leaseExpiry(p)),
                () -> assertNull(leaseOwner(p), "released, so the retry is not held by this process"),
                () -> assertEquals("fold", done.get("stage")),
                () -> assertEquals(Stub.ROWS, done.get("rows")),
                () -> assertTrue(done.containsKey("durationMs")),
                () -> assertEquals("reconcile", failed.get("stage")),
                () -> assertEquals(IllegalStateException.class.getName(), failed.get("error")),
                () -> assertTrue(failed.containsKey("durationMs")),
                () -> assertFalse(lastError.contains(SECRET_ID), lastError),
                () -> assertEquals("reconcile: " + IllegalStateException.class.getName(), lastError),
                () -> assertTrue(
                        events.stream().noneMatch(EndUsersWorkerTest::carriesSecret),
                        "no log line carries the user ID from the exception message"));
    }

    @Test
    void exhaustedJob_isDeadLettered_after5Attempts() {
        Project p = TenantFixture.bootstrap(tenants, "eu-job-exhausted").project();
        Project crashed = TenantFixture.bootstrap(tenants, "eu-job-crashed").project();
        MovingClock clock = new MovingClock(DAY_START.plus(Duration.ofMinutes(65)));
        Stub fold = new Stub("fold", attempt -> true);
        EndUsersWorker worker = worker(clock, props, new Stub("hits"), fold);
        List<String> states = new ArrayList<>();

        isolate(p, crashed);
        seedJob(crashed, "end_users", crashed.id() + ":1", "claimed", 5, "2026-06-15T00:00:00Z");
        for (int tick = 0; tick < 6; tick++) {
            isolate(p, crashed);
            worker.dailyTick();
            states.add(jobState(p));
            clock.set(clock.instant().plus(Duration.ofMinutes(10)));
        }

        assertAll(
                () -> assertEquals(
                        List.of("claimed|1|", "claimed|2|", "claimed|3|", "claimed|4|", "dead|5|", "dead|5|"), states),
                () -> assertEquals(5, fold.calls(p)),
                () -> assertEquals(
                        "dead|5|exhausted: 5 attempts (hung or crashed mid-run)",
                        jdbc.sql("SELECT status || '|' || attempts || '|' || last_error FROM job"
                                        + " WHERE kind = 'end_users' AND dedupe_key = :key")
                                .param("key", crashed.id() + ":1")
                                .query(String.class)
                                .single(),
                        "a job whose worker died past the cap is dead-lettered too"),
                () -> assertFalse(fold.days(crashed).contains(1), "it is not claimed again"));
    }

    private EndUsersWorker worker(MovingClock clock, EndUsersProperties properties, Stub hits, Stub... daily) {
        return new EndUsersWorker(jobs, hits, List.of(daily), properties, clock);
    }

    /** The end-user jobs of every other project leave, so a drain claims only these projects' jobs. */
    private void isolate(Project... keep) {
        jdbc.sql("DELETE FROM job WHERE kind IN ('end_users', 'end_user_hits') AND NOT (project_id = ANY(:pids))")
                .param("pids", Arrays.stream(keep).map(Project::id).toArray(String[]::new))
                .update();
    }

    private List<String> dailyJobs(Project p) {
        return jobRows(p, "end_users");
    }

    private List<String> hitJobs(Project p) {
        return jobRows(p, "end_user_hits");
    }

    private List<String> jobRows(Project p, String kind) {
        return jdbc.sql("""
                SELECT dedupe_key || '|' || (payload ->> 'day') || '|' || status FROM job
                 WHERE kind = :kind AND project_id = :pid ORDER BY dedupe_key
                """)
                .param("kind", kind)
                .param("pid", p.id())
                .query(String.class)
                .list();
    }

    private String jobState(Project p) {
        return jdbc.sql("""
                SELECT status || '|' || attempts || '|' || COALESCE(progress ->> 'stage', '') FROM job
                 WHERE kind = 'end_users' AND project_id = :pid
                """).param("pid", p.id()).query(String.class).single();
    }

    private String leaseExpiry(Project p) {
        return jdbc.sql("SELECT lease_expires_at FROM job WHERE kind = 'end_users' AND project_id = :pid")
                .param("pid", p.id())
                .query(String.class)
                .single();
    }

    private @Nullable String leaseOwner(Project p) {
        return jdbc.sql("SELECT lease_owner FROM job WHERE kind = 'end_users' AND project_id = :pid")
                .param("pid", p.id())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void seedJob(
            Project p, String kind, String dedupeKey, String status, int attempts, @Nullable String leaseExpiresAt) {
        jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, lease_expires_at, dedupe_key, payload,
                                 created_at, updated_at)
                VALUES (:id, :pid, :kind, :status, :attempts, :expires, :key,
                        jsonb_build_object('day', CAST(split_part(:key, ':', 2) AS integer)),
                        '2026-06-14T00:00:00Z', '2026-06-14T00:00:00Z')
                ON CONFLICT (dedupe_key) WHERE kind = 'end_users' DO NOTHING
                """)
                .param("id", Ids.ulid())
                .param("pid", p.id())
                .param("kind", kind)
                .param("status", status)
                .param("attempts", attempts)
                .param("expires", leaseExpiresAt)
                .param("key", dedupeKey)
                .update();
    }

    private void evidence(Project p, String createdAt) {
        String findingId = "f-" + Ids.ulid();
        jdbc.sql("""
                INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, status,
                                     onset_at, last_seen_at, payload, created_at, updated_at)
                VALUES (:id, :pid, 'tool_error', :id, 'classifier', 'cls', 'open', :at, :at, '{}'::jsonb, :at, :at)
                """)
                .param("id", findingId)
                .param("pid", p.id())
                .param("at", createdAt)
                .update();
        jdbc.sql("""
                INSERT INTO finding_evidence (id, project_id, finding_id, trace_id, role, created_at)
                VALUES (:id, :pid, :fid, 't-1', 'witness', :at)
                """)
                .param("id", Ids.ulid())
                .param("pid", p.id())
                .param("fid", findingId)
                .param("at", createdAt)
                .update();
    }

    private static List<ILoggingEvent> capture(Runnable run) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = ctx.getLogger("ai.tessary.endusers");
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
        try {
            run.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(before);
        }
    }

    private static ILoggingEvent single(List<ILoggingEvent> events, String event, Project p) {
        List<ILoggingEvent> matching = events.stream()
                .filter(e -> event.equals(fields(e).get("event"))
                        && p.id().equals(fields(e).get("project")))
                .toList();
        assertEquals(1, matching.size(), event);
        return matching.getFirst();
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs() == null
                ? Map.of()
                : event.getKeyValuePairs().stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value));
    }

    private static boolean carriesSecret(ILoggingEvent event) {
        String text = event.getFormattedMessage()
                + fields(event)
                + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
        return text.contains(SECRET_ID);
    }

    /** A stage that records the projects and days it ran for, and throws on the attempts {@code fails} picks. */
    private static final class Stub implements EndUsersStage {

        static final long ROWS = 12L;

        private final String name;
        private final IntPredicate fails;
        private final List<String> runs = new ArrayList<>();

        Stub(String name) {
            this(name, attempt -> false);
        }

        Stub(String name, IntPredicate fails) {
            this.name = name;
            this.fails = fails;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long run(String projectId, int day) {
            runs.add(projectId + ":" + day);
            long attempt =
                    runs.stream().filter(r -> r.startsWith(projectId + ":")).count();
            if (fails.test((int) attempt)) {
                throw new IllegalStateException("no row for user " + SECRET_ID);
            }
            return ROWS;
        }

        int calls(Project p) {
            return days(p).size();
        }

        List<Integer> days(Project p) {
            return runs.stream()
                    .filter(r -> r.startsWith(p.id() + ":"))
                    .map(r -> Integer.parseInt(r.substring(p.id().length() + 1)))
                    .toList();
        }
    }

    /** A clock a test moves between ticks; the lease and retry times compare as text, so time must pass. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
