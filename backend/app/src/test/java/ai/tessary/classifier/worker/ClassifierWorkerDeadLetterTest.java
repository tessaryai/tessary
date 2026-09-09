// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.ThrowingEncoderScorerConfig;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * A sweep that keeps throwing is dead-lettered after {@code maxAttempts} consecutive failures:
 * crossing the cap logs ERROR once, and below-cap failures stay WARN and dedup to one stacktrace
 * per streak. Once the backend recovers, the next heartbeat past the cooldown floor gives the
 * signal a fresh job instead of leaving it wedged. Runs against real pgvector Postgres
 * (Testcontainers); only the encoder scorer is faked.
 */
@SpringBootTest
@Import({ThrowingEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
// Own context on purpose: the zero dead-letter cooldown it needs would make every other class's failed job revive
// instantly.
@TestPropertySource(properties = "test.context-group=classifier-dead-letter")
class ClassifierWorkerDeadLetterTest {

    private static final int MAX_ATTEMPTS = 5;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // A near-zero cooldown lets the test drive an automatic revival without a real-time wait.
        r.add("tessary.classifier.dead-letter-cooldown-seconds", () -> "0");
    }

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    ClassifierWorker worker;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    TenantService tenants;

    @Autowired
    ThrowingEncoderScorerConfig.Toggle scorerToggle;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    void fastFailingSweep_deadLettersAtTheCap_logsErrorOnce_thenRecoversOnceHealthy() {
        String pid =
                TenantFixture.bootstrap(tenants, "signal-deadletter").project().id();
        // tick() sweeps every ENABLED classifier, and a project seeds the whole built-in catalog on
        // creation. Disable the rest instead of deleting: catalog reconcile would re-insert them enabled.
        jdbc.sql("UPDATE classifier SET enabled = FALSE WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        String now = Instant.now().toString();

        fx.spanSeed(pid)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(SubstrateV2Fixtures.sessionId())
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(Instant.parse(now))
                .payload(ClassifierObservations.userInput("this is frustrating, you're not listening to me"), "ok")
                .write();

        String classifierId = Ids.ulid();
        signals.insert(new ClassifierRow(
                classifierId,
                pid,
                "frustration-dl",
                "Frustration (dead-letter test)",
                null,
                BuiltInDetector.Kind.FRUSTRATION,
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));

        Logger workerLog = (Logger) LoggerFactory.getLogger(ClassifierWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLog.addAppender(appender);
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                worker.tick();
                JobSnapshot job = awaitJobSettled(classifierId);
                assertEquals(attempt, job.attempts(), "attempt " + attempt);
                if (attempt < MAX_ATTEMPTS) {
                    assertEquals(ClassifierJobRow.FAILED, job.status(), "below the cap stays retryable");
                } else {
                    assertEquals(ClassifierJobRow.DEAD, job.status(), "the 5th consecutive failure crosses the cap");
                }
            }

            List<ILoggingEvent> errors = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .toList();
            assertEquals(1, errors.size(), "crossing the cap emits exactly one ERROR log, not one per heartbeat");
            assertTrue(
                    errors.get(0).getFormattedMessage().contains("dead-lettered"),
                    "the ERROR log names the dead-letter transition: "
                            + errors.get(0).getFormattedMessage());
            long warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .count();
            assertEquals(
                    1,
                    warns,
                    "the below-cap failures stay WARN and dedup to one stacktrace per streak, "
                            + "not one per heartbeat");

            // /classify recovers: the next heartbeat (cooldown=0) revives the dead job and this sweep succeeds.
            scorerToggle.setThrowing(false);
            worker.tick();
            JobSnapshot revived = awaitJobStatus(classifierId, ClassifierJobRow.DONE);
            assertEquals(
                    0,
                    revived.attempts(),
                    "a genuinely-new successful sweep resets the attempt budget — dead-lettering didn't "
                            + "permanently wedge the signal");
        } finally {
            workerLog.detachAppender(appender);
        }
    }

    private record JobSnapshot(String status, int attempts) {}

    /** Poll until the async-dispatched sweep leaves {@code claimed} (settles to failed/dead/done). */
    private JobSnapshot awaitJobSettled(String classifierId) {
        for (int i = 0; i < 100; i++) {
            JobSnapshot job = jobRow(classifierId).orElse(null);
            if (job != null && !ClassifierJobRow.CLAIMED.equals(job.status())) return job;
            sleep(100);
        }
        throw new AssertionError("signal job for " + classifierId + " never settled past 'claimed'");
    }

    private JobSnapshot awaitJobStatus(String classifierId, String status) {
        for (int i = 0; i < 100; i++) {
            JobSnapshot job = jobRow(classifierId).orElse(null);
            if (job != null && status.equals(job.status())) return job;
            sleep(100);
        }
        throw new AssertionError("signal job for " + classifierId + " never reached status=" + status);
    }

    private Optional<JobSnapshot> jobRow(String classifierId) {
        return jdbc.sql("SELECT status, attempts FROM job WHERE kind = 'classifier' AND dedupe_key = :sid")
                .param("sid", classifierId)
                .query((rs, n) -> new JobSnapshot(rs.getString("status"), rs.getInt("attempts")))
                .optional();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
