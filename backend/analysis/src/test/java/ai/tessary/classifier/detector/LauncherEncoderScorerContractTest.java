// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.EncoderScorer.Response;
import ai.tessary.classifier.detector.EncoderScorer.ResponseScore;
import ai.tessary.classifier.detector.EncoderScorer.Span;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The backend half of the contract {@code classifiers/groundedness/serve.py} is tested against: the same two
 * fixtures, read from the repository rather than copied, so one edit to them moves both sides. The scorer's
 * request body for the fixture's responses must be the fixture request, key for key, and the fixture response
 * must parse into the scores and spans it states.
 */
class LauncherEncoderScorerContractTest {

    /** The fixtures, from the module directory surefire runs in. */
    private static final Path CONTRACT = Path.of("../../classifiers/groundedness/contract");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ServerSocket socket;
    private Thread acceptor;
    private LauncherEncoderScorer scorer;
    private final AtomicReference<String> received = new AtomicReference<>();
    private byte[] reply;

    @BeforeEach
    void start() throws IOException {
        reply = Files.readAllBytes(CONTRACT.resolve("classify-response.json"));
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serveLoop, "contract-classify-server");
        acceptor.setDaemon(true);
        acceptor.start();
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("http://127.0.0.1:" + socket.getLocalPort());
        props.getEncoder().setApiKey("k");
        scorer = new LauncherEncoderScorer(props, MAPPER, reason -> {});
    }

    @AfterEach
    void stop() throws IOException, InterruptedException {
        socket.close();
        acceptor.join(2_000);
    }

    @Test
    void theRequestBodyIsTheFixtureRequest() throws IOException {
        JsonNode fixture = request();

        scorer.scoreResponses(fixture.get("head").asText(), responses(fixture));

        assertEquals(fixture, MAPPER.readTree(received.get()), "the scorer must send what serve.py is tested with");
    }

    @Test
    void theFixtureResponseParsesIntoItsScoresAndSpans() throws IOException {
        JsonNode fixture = request();
        JsonNode expected = MAPPER.readTree(reply).get("scores");

        List<ResponseScore> scores = scorer.scoreResponses(fixture.get("head").asText(), responses(fixture));

        assertEquals(expected.size(), scores.size());
        assertTrue(scores.stream().anyMatch(s -> s.unsupported() >= 0.975), "the fixture holds a flagged answer");
        for (int i = 0; i < scores.size(); i++) {
            JsonNode want = expected.get(i);
            ResponseScore got = scores.get(i);
            assertTrue(got.scored());
            assertEquals(want.get("unsupported").asDouble(), got.unsupported());
            assertEquals(want.get("conflict").asDouble(), got.conflict());
            List<Span> spans = new ArrayList<>();
            for (JsonNode s : want.get("spans")) {
                spans.add(new Span(
                        s.get("start").asInt(),
                        s.get("end").asInt(),
                        s.get("unsupported").asDouble(),
                        s.get("conflict").asDouble()));
            }
            assertFalse(spans.isEmpty(), "every fixture score carries its sentences");
            assertEquals(spans, got.spans());
        }
    }

    private static JsonNode request() throws IOException {
        return MAPPER.readTree(Files.readAllBytes(CONTRACT.resolve("classify-request.json")));
    }

    private static List<Response> responses(JsonNode request) {
        List<Response> out = new ArrayList<>();
        for (JsonNode r : request.get("responses")) {
            List<String> passages = new ArrayList<>();
            r.get("passages").forEach(p -> passages.add(p.asText()));
            JsonNode question = r.get("question");
            out.add(new Response(
                    passages,
                    question == null ? null : question.asText(),
                    r.get("answer").asText()));
        }
        return out;
    }

    private void serveLoop() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                InputStream in = client.getInputStream();
                int contentLength = 0;
                for (String line : readHead(in).split("\r\n")) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(
                                line.substring("content-length:".length()).trim());
                    }
                }
                received.set(new String(in.readNBytes(contentLength), StandardCharsets.UTF_8));
                OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + reply.length
                                + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(reply);
                out.flush();
            } catch (IOException e) {
                return;
            }
        }
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
}
