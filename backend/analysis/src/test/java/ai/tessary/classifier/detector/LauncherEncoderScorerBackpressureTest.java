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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the token-head client does when the encoder pushes back, against a loopback {@link
 * ServerSocket} HTTP responder (forbidden-apis bans {@code com.sun.net.httpserver}, the same reason
 * {@code HttpConformanceEncoderTest} rolls its own): a throttled request (429) is retried with
 * backoff and then succeeds; a refused one (400) is bisected down to the single offending response,
 * which comes back UNSCORED while the rest are scored; requests are sized by estimated tokens, so a
 * long response travels alone; and a connection that never opens is unreachable, while a 500 or a
 * 401 is a fault.
 */
class LauncherEncoderScorerBackpressureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ServerSocket socket;
    private Thread acceptor;
    private LauncherEncoderScorer scorer;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int throttleFirst = 0;
    private volatile int rejectAnswersLongerThan = Integer.MAX_VALUE;
    private volatile int failWith = 0;
    private final List<String> unreachable = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serveLoop, "stub-classify-server");
        acceptor.setDaemon(true);
        acceptor.start();
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("http://127.0.0.1:" + socket.getLocalPort());
        props.getEncoder().setApiKey("k");
        scorer = new LauncherEncoderScorer(props, MAPPER, unreachable::add);
    }

    @AfterEach
    void stop() throws IOException, InterruptedException {
        socket.close();
        acceptor.join(2_000);
    }

    private void serveLoop() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                handleOne(client);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handleOne(Socket client) throws IOException {
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
        int status;
        String extraHeader = "";
        String payload;
        if (failWith != 0) {
            status = failWith;
            payload = "{\"error\":\"stub failure\"}";
        } else if (n <= throttleFirst) {
            status = 429;
            extraHeader = "Retry-After: 0\r\n";
            payload = "{\"error\":\"at capacity\"}";
        } else {
            boolean refused = false;
            for (JsonNode r : responses) {
                if (r.get("answer").asText().length() > rejectAnswersLongerThan) refused = true;
            }
            if (refused) {
                status = 400;
                payload = "{\"error\":\"groundedness answer too long to score\"}";
            } else {
                status = 200;
                StringBuilder sb = new StringBuilder("{\"scores\":[");
                for (int i = 0; i < responses.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append("{\"unsupported\":0.9,\"conflict\":0.1,\"spans\":[]}");
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

    @Test
    void aThrottledRequestIsRetriedAndThenScored() {
        throttleFirst = 2;
        List<ResponseScore> out = scorer.scoreResponses("groundedness", List.of(r("a"), r("b")));
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(ResponseScore::scored));
        assertEquals(3, requests.get(), "two 429s, then the one that answered");
    }

    @Test
    void throttlingPastTheRetryBudgetFailsTheRequestAsBefore() {
        throttleFirst = LauncherEncoderScorer.MAX_THROTTLE_RETRIES + 5;
        assertThrows(IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));
        assertEquals(LauncherEncoderScorer.MAX_THROTTLE_RETRIES + 1, requests.get());
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

    @Test
    void aRefusedConnectionIsUnreachableAndMarksTheModelDown() throws IOException {
        socket.close();

        EncoderUnreachableException e = assertThrows(
                EncoderUnreachableException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertTrue(LauncherEncoderScorer.isUnreachable(e), String.valueOf(e.getCause()));
        assertEquals(1, unreachable.size(), "the availability is told at once");
        assertTrue(unreachable.getFirst().startsWith("unreachable: "), unreachable.getFirst());
    }

    @Test
    void aServerErrorIsAFaultNotUnreachable() {
        failWith = 500;

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertFalse(e instanceof EncoderUnreachableException, "a model that answers 500 is broken, not asleep");
        assertTrue(unreachable.isEmpty());
    }

    @Test
    void anUnauthorisedAnswerIsAFaultNotUnreachable() {
        failWith = 401;

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> scorer.scoreResponses("groundedness", List.of(r("a"))));

        assertFalse(e instanceof EncoderUnreachableException, "a wrong key is a fault the attempts should count");
        assertTrue(unreachable.isEmpty());
    }

    @Test
    void requestsAreSizedByEstimatedTokensSoALongResponseTravelsAlone() {
        String longAnswer = "x".repeat(60_000); // ~15k estimated tokens
        List<Response> in = List.of(r("a"), r(longAnswer), r(longAnswer), r("b"));
        List<List<Response>> chunks = LauncherEncoderScorer.chunkResponses(in, 16, Long.MAX_VALUE, 24_000);
        // ~15k tokens each: the short answer rides with the first long one (15k < 24k), the second
        // long one would take the pair to 30k and so starts its own chunk, and the trailing short
        // answer rides with it. Count alone (16) would have put all four in one request.
        assertEquals(List.of(2, 2), chunks.stream().map(List::size).toList());
    }
}
