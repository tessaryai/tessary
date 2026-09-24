// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.config.GroundednessProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.plan.EncoderAvailability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The groundedness model being down pauses its sweep and never costs it an attempt, against real
 * Postgres and a loopback stand-in for {@code serve.py}: nothing is enqueued while the model is down;
 * a job claimed before it went down is handed back with its attempt count unchanged; a refused
 * connection mid-sweep does the same, so any number of them in a row never dead-letter; and a 500 is
 * still a fault that counts. Production mode sleeps after a caught-up sweep; dev mode never does.
 *
 * <p>Own context, with the heartbeat and the health probe pushed out of reach: this class moves the
 * encoder's URL and the mode on shared beans, and drives each sweep itself, so neither a background
 * tick nor a background probe may run in between.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "test.context-group=classifier-encoder-gate",
            "tessary.classifier.heartbeat-ms=86400000",
            "tessary.observer.encoder.probe-interval-ms=86400000"
        })
class ClassifierWorkerEncoderGateIntegrationTest {

    private static final long COOLDOWN_SECONDS = 1_800;
    private static final String CALL_SITE = "summarizer";

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
    ClassifierService classifiers;

    @Autowired
    ClassifierRepository rows;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObserverProperties observer;

    @Autowired
    EncoderAvailability encoder;

    @Autowired
    GroundednessProperties groundedness;

    private final StubModel model = new StubModel();
    private String previousUrl = "";

    @BeforeEach
    void start() throws IOException {
        previousUrl = observer.getEncoder().getUrl();
        observer.getEncoder().setApiKey("k");
        model.start();
    }

    @AfterEach
    void stop() throws IOException {
        model.stop();
        observer.getEncoder().setUrl(previousUrl);
        encoder.refresh();
        groundedness.setClassifierMode("dev");
    }

    @Test
    void encoderDownMeansNothingIsEnqueued() {
        Setup s = setup("gate-enqueue");
        modelDown();

        classifiers.enqueueEnabled(s.pid());
        assertTrue(jobs.findByClassifier(s.pid(), s.classifierId()).isEmpty(), "no sweep while the model is down");

        modelUp();
        classifiers.enqueueEnabled(s.pid());
        assertEquals(ClassifierJobRow.PENDING, job(s).status(), "the model answers: the sweep is enqueued");
    }

    @Test
    void aClaimedJobWithTheEncoderDownIsReleasedWithTheSameAttemptCount() {
        Setup s = setup("gate-claimed");
        jobs.enqueue(s.pid(), s.classifierId(), COOLDOWN_SECONDS);
        int before = job(s).attempts();
        ClassifierJobRow claimed = claim(s);
        assertEquals(before + 1, claimed.attempts(), "the claim took an attempt");

        modelDown();
        worker.sweepForTest(claimed);

        ClassifierJobRow after = job(s);
        assertEquals(before, after.attempts(), "handing it back returns the attempt");
        assertEquals(ClassifierJobRow.DONE, after.status());
        assertNull(after.leaseOwner(), "and releases the lease");
        assertNull(after.cursorAt(), "and scores nothing");
    }

    @Test
    void aRefusedConnectionReleasesWithoutAnAttemptAndNeverDeadLetters() throws IOException {
        Setup s = setup("gate-refused");
        jobs.enqueue(s.pid(), s.classifierId(), COOLDOWN_SECONDS);
        String refusing = "http://127.0.0.1:" + closedPort();

        for (int i = 0; i < 7; i++) {
            // Up at the last probe, gone by the time the sweep connects: a GPU instance that stopped
            // between the two.
            modelUp();
            observer.getEncoder().setUrl(refusing);
            worker.sweepForTest(claim(s));

            ClassifierJobRow after = job(s);
            assertNotEquals(ClassifierJobRow.DEAD, after.status(), "refusal " + (i + 1) + " never dead-letters");
            assertEquals(0, after.attempts(), "refusal " + (i + 1) + " spends no attempt");
            assertNull(after.cursorAt(), "the answer was never scored, so the cursor stays");
            assertFalse(encoder.available(), "the refusal marks the model down at once");
        }
    }

    @Test
    void aServerErrorStillCountsAndDeadLettersAtTheCap() {
        Setup s = setup("gate-500");
        jobs.enqueue(s.pid(), s.classifierId(), COOLDOWN_SECONDS);
        model.classifyStatus = 500;
        modelUp();

        for (int attempt = 1; attempt <= 5; attempt++) {
            worker.sweepForTest(claim(s));
            ClassifierJobRow after = job(s);
            assertEquals(attempt, after.attempts(), "a 500 spends attempt " + attempt);
            assertEquals(attempt < 5 ? ClassifierJobRow.FAILED : ClassifierJobRow.DEAD, after.status());
        }
        assertTrue(encoder.available(), "a model that answers 500 is up and broken, not down");
    }

    @Test
    void aSweepThatCatchesUpRecordsWhen() {
        Setup s = setup("gate-caught-up");
        jobs.enqueue(s.pid(), s.classifierId(), COOLDOWN_SECONDS);
        modelUp();
        Instant before = Instant.now();

        worker.sweepForTest(claim(s));

        ClassifierJobRow after = job(s);
        assertEquals(ClassifierJobRow.DONE, after.status());
        assertEquals(0, after.attempts());
        Instant caughtUp = jobs.caughtUpAt(s.pid(), s.classifierId()).orElseThrow();
        assertFalse(caughtUp.isBefore(before), "caught_up_at is this sweep's");
        assertEquals(1, model.classifyCalls.get(), "the answer was scored once");
    }

    @Test
    void productionModeSleepsAfterACaughtUpSweepAndWakesAfterTheSleep() {
        Setup s = setup("gate-production");
        jobs.enqueue(s.pid(), s.classifierId(), COOLDOWN_SECONDS);
        groundedness.setClassifierMode("production");
        modelUp();

        caughtUp(s, Instant.now().minus(Duration.ofMinutes(10)));
        classifiers.enqueueEnabled(s.pid());
        assertEquals(ClassifierJobRow.DONE, job(s).status(), "10 minutes after catching up: still asleep");

        caughtUp(s, Instant.now().minus(Duration.ofMinutes(31)));
        classifiers.enqueueEnabled(s.pid());
        assertEquals(ClassifierJobRow.PENDING, job(s).status(), "past the 30-minute sleep: enqueued");
    }

    @Test
    void devModeNeverSleeps() {
        Setup s = setup("gate-dev");
        jobs.enqueue(s.pid(), s.classifierId(), COOLDOWN_SECONDS);
        modelUp();

        caughtUp(s, Instant.now().minus(Duration.ofMinutes(1)));
        classifiers.enqueueEnabled(s.pid());

        assertEquals(ClassifierJobRow.PENDING, job(s).status(), "dev sweeps whenever the model answers");
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private record Setup(String pid, String classifierId) {}

    /**
     * A project whose only enabled classifier is groundedness, with one answer it will score: a
     * {@code summarize} call site, so the prompt is the premise, and an answer with checkable claims.
     */
    private Setup setup(String name) {
        String pid = TenantFixture.bootstrap(tenants, name).project().id();
        jdbc.sql("UPDATE classifier SET enabled = FALSE WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        jdbc.sql("INSERT INTO call_site (project_id, id, shape) VALUES (:pid, :id, 'summarize')")
                .param("pid", pid)
                .param("id", CALL_SITE)
                .update();
        new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc)
                .spanSeed(pid)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(SubstrateV2Fixtures.sessionId())
                .callSiteId(CALL_SITE)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(Instant.now().minusSeconds(60))
                .payload(
                        ClassifierObservations.userInput(
                                "Summarize: the Eiffel Tower in Paris was completed in 1889 for the World's Fair."),
                        ClassifierObservations.assistantOutput(
                                "The Eiffel Tower in Berlin was completed in 1925 by Gustave Eiffel."))
                .write();
        String classifierId = Ids.ulid();
        String now = Instant.now().toString();
        rows.insert(new ClassifierRow(
                classifierId,
                pid,
                "groundedness-gate",
                "Groundedness (gate test)",
                null,
                BuiltInDetector.Kind.GROUNDEDNESS,
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                now,
                now));
        return new Setup(pid, classifierId);
    }

    /** Claim this classifier's job as the worker would, without sweeping anyone else's. */
    private ClassifierJobRow claim(Setup s) {
        jdbc.sql("""
            UPDATE job SET status = 'claimed', lease_owner = :owner, lease_expires_at = :expires,
                           attempts = attempts + 1, updated_at = :now
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
            """)
                .param("owner", worker.leaseOwnerForTest())
                .param("expires", Instant.now().plusSeconds(300).toString())
                .param("now", Instant.now().toString())
                .param("pid", s.pid())
                .param("sid", s.classifierId())
                .update();
        return job(s);
    }

    private ClassifierJobRow job(Setup s) {
        return jobs.findByClassifier(s.pid(), s.classifierId()).orElseThrow();
    }

    private void caughtUp(Setup s, Instant at) {
        jdbc.sql("""
            UPDATE job SET status = 'done', payload = payload || jsonb_build_object('caught_up_at', CAST(:at AS text))
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
            """)
                .param("at", at.toString())
                .param("pid", s.pid())
                .param("sid", s.classifierId())
                .update();
    }

    private void modelUp() {
        observer.getEncoder().setUrl(model.url());
        EncoderAvailability.Snapshot snap = encoder.refresh();
        assertTrue(snap.available(), snap.reason());
    }

    private void modelDown() {
        observer.getEncoder().setUrl("");
        assertFalse(encoder.refresh().available());
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /**
     * {@code serve.py}'s two routes on a loopback socket: {@code /healthz} lists the groundedness head,
     * and {@code /classify} answers {@link #classifyStatus}, with one clean score per answer on a 200.
     */
    private static final class StubModel {
        volatile int classifyStatus = 200;
        final AtomicInteger classifyCalls = new AtomicInteger();
        private @Nullable ServerSocket socket;
        private @Nullable Thread acceptor;

        void start() throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            acceptor = new Thread(this::serveLoop, "stub-serve-py");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void stop() throws IOException {
            if (socket != null) socket.close();
        }

        String url() {
            ServerSocket listening = socket;
            if (listening == null) throw new IllegalStateException("stub model not started");
            return "http://127.0.0.1:" + listening.getLocalPort();
        }

        private void serveLoop() {
            ServerSocket listening = socket;
            while (listening != null && !listening.isClosed()) {
                try (Socket client = listening.accept()) {
                    handle(client);
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket client) throws IOException {
            InputStream in = client.getInputStream();
            StringBuilder head = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                head.append((char) c);
                if (head.toString().endsWith("\r\n\r\n")) break;
            }
            String[] lines = head.toString().split("\r\n");
            int contentLength = 0;
            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(
                            line.substring("content-length:".length()).trim());
                }
            }
            in.readNBytes(contentLength);
            int status;
            String body;
            if (lines[0].startsWith("GET /healthz")) {
                status = 200;
                body = "{\"ok\":true,\"heads\":[\"groundedness\"]}";
            } else {
                classifyCalls.incrementAndGet();
                status = classifyStatus;
                body = status == 200
                        ? "{\"scores\":[{\"unsupported\":0.1,\"conflict\":0.0,\"spans\":[]}]}"
                        : "{\"error\":\"stub failure\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 " + status + " Stub\r\nContent-Type: application/json\r\nContent-Length: "
                            + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }
    }
}
