// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.EncoderScorer.Response;
import ai.tessary.classifier.detector.EncoderScorer.ResponseScore;
import ai.tessary.config.ObserverProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The token-head client under encoder pushback, against a loopback {@link ServerSocket} responder (forbidden-apis
 * bans {@code com.sun.net.httpserver}). 429/503 retries with backoff, then fails past the budget; a 400 is bisected
 * to the one offending response, which comes back unscored; requests are sized by count and estimated tokens; a non-
 * numeric score is a fault; at most {@code encoder.max-inflight} requests are open; a connection that never opens is
 * unreachable, while a 500 or 401 is a fault.
 */
class LauncherEncoderScorerBackpressureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ServerSocket socket;
    private Thread acceptor;
    private LauncherEncoderScorer scorer;
    private final AtomicInteger requests = new AtomicInteger();
    /** How many responses each request carried, in arrival order. */
    private final List<Integer> responsesPerRequest = new CopyOnWriteArrayList<>();

    private volatile int throttleFirst = 0;
    private volatile int throttleStatus = 429;
    /** The Retry-After a throttled reply carries; null sends none. */
    private volatile @Nullable String retryAfter = "0";

    private volatile int rejectAnswersLongerThan = Integer.MAX_VALUE;
    private volatile int failWith = 0;
    /** One score entry of a 200 reply, repeated per response. */
    private volatile String scoreEntry = "{\"unsupported\":0.9,\"conflict\":0.1,\"spans\":[]}";
    /** When set, a 200 reply carries this body verbatim instead of the scores. */
    private volatile @Nullable String rawBody = null;
    /** When set, the reply is bytes that are not HTTP at all. */
    private volatile boolean notHttp = false;
    /** When set, every request is held open until it counts down. */
    private volatile @Nullable CountDownLatch hold = null;

    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger peakOpen = new AtomicInteger();
    private final List<String> unreachable = new CopyOnWriteArrayList<>();
    /** The throttle backoff's waits, recorded instead of taken. */
    private final List<Long> waits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serveLoop, "stub-classify-server");
        acceptor.setDaemon(true);
        acceptor.start();
        scorer = scorer(new ObserverProperties());
    }

    private LauncherEncoderScorer scorer(ObserverProperties props) {
        return scorer(props, socket.getLocalPort());
    }

    private LauncherEncoderScorer scorer(ObserverProperties props, int port) {
        props.getEncoder().setUrl("http://127.0.0.1:" + port);
        props.getEncoder().setApiKey("k");
        return new LauncherEncoderScorer(props, MAPPER, unreachable::add, waits::add);
    }

    @AfterEach
    void stop() throws IOException, InterruptedException {
        socket.close();
        acceptor.join(2_000);
    }

    private void serveLoop() {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return;
            }
            Thread handler = new Thread(
                    () -> {
                        try (client) {
                            handleOne(client);
                        } catch (IOException | InterruptedException e) {
                            // the client is gone
                        }
                    },
                    "stub-classify-handler");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handleOne(Socket client) throws IOException, InterruptedException {
        InputStream in = client.getInputStream();
        String head = readHead(in);
        int contentLength = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(
                        line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = in.readNBytes(contentLength);
        int n = requests.incrementAndGet();
        JsonNode responses =
                MAPPER.readTree(new String(body, StandardCharsets.UTF_8)).get("responses");
        responsesPerRequest.add(responses.size());
        @Nullable CountDownLatch held = hold;
        if (held != null) {
            peakOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
            held.await(10, TimeUnit.SECONDS);
            open.decrementAndGet();
        }
        if (notHttp) {
            OutputStream raw = client.getOutputStream();
            raw.write("garbage\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            raw.flush();
            return;
        }
        int status;
        String extraHeader = "";
        String payload;
        String body200 = rawBody;
        if (failWith != 0) {
            status = failWith;
            payload = "{\"error\":\"stub failure\"}";
        } else if (n <= throttleFirst) {
            status = throttleStatus;
            String after = retryAfter;
            extraHeader = after == null ? "" : "Retry-After: " + after + "\r\n";
            payload = "{\"error\":\"at capacity\"}";
        } else {
            boolean refused = false;
            for (JsonNode r : responses) {
                if (r.get("answer").asText().length() > rejectAnswersLongerThan) refused = true;
            }
            if (body200 != null) {
                status = 200;
                payload = body200;
            } else if (refused) {
                status = 400;
                payload = "{\"error\":\"groundedness answer too long to score\"}";
            } else {
                status = 200;
                StringBuilder sb = new StringBuilder("{\"scores\":[");
                for (int i = 0; i < responses.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(scoreEntry);
                }
                payload = sb.append("]}").toString();
            }
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 " + status + " Stub\r\n" + extraHeader + "Content-Type: application/json\r\n"
                        + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int p3 = -1;
        int p2 = -1;
        int p1 = -1;
        int c;
        while ((c = in.read()) != -1) {
            head.write(c);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && c == '\n') break;
            p3 = p2;
            p2 = p1;
            p1 = c;
        }
        return head.toString(StandardCharsets.UTF_8);
    }

    private static Response r(String answer) {
        return new Response(List.of("passage"), "q?", answer);
    }

    /** A numeric Retry-After is waited instead of the backoff. */
    @Test
    void aThrottledRequestIsRetriedAndThenScored() {
        throttleFirst = 2;
        retryAfter = "3";
        List<ResponseScore> out = scorer.scoreResponses("groundedness", List.of(r("a"), r("b")));
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(ResponseScore::scored));
        assertEquals(3, requests.get(), "two 429s, then the one that answered");
        assertEquals(List.of(3_000L, 3_000L), waits);
    }

    /**
     * No Retry-After: 2 s doubling, four waits between five sends, then fail. Zero backoff would hammer a throttled
     * encoder.
     */
    @Test
    void throttlingPastTheRetryBudgetBacksOffDoublingThenFailsTheRequest() {
        throttleFirst = 100;
        throttleStatus = 503;
        retryAfter = null;

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertFalse(e instanceof EncoderUnreachableException, "a throttled encoder is up, and failing the sweep");
        assertEquals(List.of(2_000L, 4_000L, 8_000L, 16_000L), waits);
        assertEquals(5, requests.get(), "the first send and four retries");
    }

    /** Node serialises a NaN score as null; read as 0.0 it would record the answer as clean. */
    @Test
    void aNonNumericScoreIsAFaultNotACleanZero() {
        scoreEntry = "{\"unsupported\":null,\"conflict\":0.1,\"spans\":[]}";

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertFalse(e instanceof EncoderUnreachableException, "the encoder answered; its answer is the fault");
    }

    @Test
    void aRefusedResponseIsIsolatedAndTheRestAreScored() {
        rejectAnswersLongerThan = 10;
        List<ResponseScore> out = scorer.scoreResponses(
                "groundedness", List.of(r("short"), r("this answer is far too long"), r("also short"), r("tiny")));
        assertEquals(4, out.size());
        assertTrue(out.get(0).scored());
        assertFalse(out.get(1).scored(), "the refused response comes back UNSCORED, never as a clean 0.0");
        assertTrue(out.get(2).scored());
        assertTrue(out.get(3).scored());
        assertTrue(Double.isNaN(out.get(1).unsupported()));
    }

    /**
     * The target is the local port of an open client connection: nothing listens, and the kernel hands the port to no
     * one else while it is held. A closed port could be reused by another listener; a merely bound socket drops the
     * SYN on macOS.
     */
    @Test
    void aRefusedConnectionIsUnreachableAndMarksTheModelDown() throws IOException {
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                Socket held = new Socket(peer.getInetAddress(), peer.getLocalPort())) {
            LauncherEncoderScorer refused = scorer(new ObserverProperties(), held.getLocalPort());

            EncoderUnreachableException e = assertThrows(
                    EncoderUnreachableException.class, () -> refused.scoreResponses("groundedness", List.of(r("a"))));

            assertTrue(LauncherEncoderScorer.isUnreachable(e), String.valueOf(e.getCause()));
        }

        assertEquals(1, unreachable.size(), "the availability is told at once");
        assertTrue(unreachable.getFirst().startsWith("unreachable: "), unreachable.getFirst());
    }

    /** A model answering 500 is broken, not asleep, and a wrong key (401) is a fault the attempts should count. */
    @ParameterizedTest
    @ValueSource(ints = {500, 401})
    void aServerErrorIsAFaultNotUnreachable(int status) {
        failWith = status;

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertFalse(e instanceof EncoderUnreachableException);
        assertTrue(unreachable.isEmpty());
    }

    @Test
    void requestsAreSizedByEstimatedTokensSoALongResponseTravelsAlone() {
        String longAnswer = "x".repeat(60_000); // ~15k estimated tokens
        // The short answer rides with the first long one; the second would reach 30k tokens so it starts its own. By
        // count alone (16) all four would share one request.
        List<ResponseScore> out =
                scorer.scoreResponses("groundedness", List.of(r("a"), r(longAnswer), r(longAnswer), r("b")));

        assertEquals(List.of(2, 2), responsesPerRequest);
        assertEquals(4, out.size());
    }

    @Test
    void requestsCarryAtMost16Responses() {
        List<Response> in = new ArrayList<>();
        for (int i = 0; i < 17; i++) in.add(r("a" + i));

        List<ResponseScore> out = scorer.scoreResponses("groundedness", in);

        assertEquals(List.of(16, 1), responsesPerRequest);
        assertEquals(17, out.size());
    }

    /** With max-inflight 1, a second sweep waits for the permit, so the encoder never sees two requests at once. */
    @Test
    void noMoreThanMaxInflightRequestsAreOpenAtOnce() throws InterruptedException {
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setMaxInflight(1);
        LauncherEncoderScorer oneAtATime = scorer(props);
        CountDownLatch release = new CountDownLatch(1);
        hold = release;
        AtomicReference<List<ResponseScore>> first = new AtomicReference<>();
        AtomicReference<List<ResponseScore>> second = new AtomicReference<>();
        Thread a = new Thread(() -> first.set(oneAtATime.scoreResponses("groundedness", List.of(r("a")))));
        Thread b = new Thread(() -> second.set(oneAtATime.scoreResponses("groundedness", List.of(r("b")))));

        a.start();
        awaitTrue(() -> open.get() == 1, "the first request reaches the encoder");
        b.start();
        // b either queues for the permit (right) or reaches the encoder beside a (wrong).
        awaitTrue(() -> open.get() == 2 || waitsOnASemaphore(b), "the second sweep posts or queues");
        release.countDown();
        a.join(10_000);
        b.join(10_000);

        assertEquals(1, peakOpen.get());
        assertEquals(2, requests.get());
        assertEquals(
                1,
                Objects.requireNonNull(first.get(), "the first sweep returned").size());
        assertEquals(
                1,
                Objects.requireNonNull(second.get(), "the second sweep returned")
                        .size());
    }

    /** Whether {@code t} is parked in a {@link Semaphore} acquire, whose park names the semaphore's sync as blocker. */
    private static boolean waitsOnASemaphore(Thread t) {
        return LockSupport.getBlocker(t) instanceof AbstractQueuedSynchronizer sync
                && sync.getClass().getName().startsWith(Semaphore.class.getName() + "$");
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting until " + what);
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
    }

    /** An encoder with no URL configured fails the sweep before anything is sent, naming the missing setting. */
    @Test
    void anUnconfiguredEncoderFailsBeforeSending() {
        ObserverProperties props = new ObserverProperties();
        LauncherEncoderScorer unconfigured = new LauncherEncoderScorer(props, MAPPER, unreachable::add, waits::add);

        assertThrows(IllegalStateException.class, () -> unconfigured.scoreResponses("groundedness", List.of(r("a"))));
        assertEquals(0, requests.get());
        assertTrue(unreachable.isEmpty(), "a missing setting is a fault, not a model that is down");
    }

    /**
     * An interrupt during the throttle backoff ends the request and keeps the thread's interrupt flag, so a
     * cancelled sweep stops instead of posting again.
     */
    @Test
    void anInterruptDuringTheBackoffEndsTheRequestAndKeepsTheFlag() {
        throttleFirst = 1;
        LauncherEncoderScorer interrupted =
                new LauncherEncoderScorer(scorerProps(), MAPPER, unreachable::add, millis -> {
                    throw new InterruptedException("sweep cancelled");
                });

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> interrupted.scoreResponses("groundedness", List.of(r("a"))));

        assertTrue(Thread.interrupted(), "the interrupt is restored for the caller, then cleared here");
        assertTrue(e.getCause() instanceof InterruptedException, String.valueOf(e.getCause()));
        assertEquals(1, requests.get(), "no retry after the interrupt");
    }

    /** A non-JSON 200 is a fault with no chained cause: Jackson's message quotes the body into the logs. */
    @Test
    void anUnparseableReplyIsAFaultWithNoChainedBody() {
        rawBody = "<html>secret-bearing proxy page</html>";

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertFalse(e instanceof EncoderUnreachableException);
        assertEquals(null, e.getCause());
        assertTrue(unreachable.isEmpty());
    }

    /** A connection that opened and then answered something other than HTTP is a fault, not an absent model. */
    @Test
    void aReplyThatIsNotHttpIsATransportFaultNotUnreachable() {
        notHttp = true;

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertFalse(e instanceof EncoderUnreachableException, String.valueOf(e.getCause()));
        assertTrue(e.getCause() instanceof IOException, String.valueOf(e.getCause()));
        assertTrue(unreachable.isEmpty(), "the model answered; marking it down would pause a live encoder");
    }

    private ObserverProperties scorerProps() {
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("http://127.0.0.1:" + socket.getLocalPort());
        props.getEncoder().setApiKey("k");
        return props;
    }
}
