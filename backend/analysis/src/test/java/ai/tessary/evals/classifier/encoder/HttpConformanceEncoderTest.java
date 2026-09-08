// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.config.ObserverProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpConformanceEncoder} against an in-process stub of the classify-service's
 * {@code POST /embed}: the happy path (vectors in order, auth header, chunking, caching) and every
 * refusal the fail-loud contract demands — checkpoint mismatch, service error, dimension mismatch,
 * short vector count, non-numeric components, unconfigured endpoint. The stub is a minimal
 * loopback {@link ServerSocket} HTTP responder (forbidden-apis bans {@code com.sun.net.httpserver}
 * as a non-portable runtime class, and the backend deliberately carries no HTTP-mock test
 * dependency). No real model, no network beyond the loopback listener.
 */
class HttpConformanceEncoderTest {

    private static final String CHECKPOINT = "Alibaba-NLP/gte-large-en-v1.5";
    private static final String API_KEY = "test-secret";

    /** One canned reply: HTTP status + JSON body (null body serves an empty object). */
    private record Reply(int status, @Nullable ObjectNode body) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> authHeaders = new ArrayList<>();

    /** Set per test: maps the request's {@code texts} to the stubbed response. */
    private volatile Function<List<String>, Reply> handler = texts -> new Reply(500, null);

    private ServerSocket socket;
    private Thread acceptor;
    private HttpConformanceEncoder encoder;

    @BeforeEach
    void start() throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serveLoop, "stub-embed-server");
        acceptor.setDaemon(true);
        acceptor.start();
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("http://127.0.0.1:" + socket.getLocalPort());
        props.getEncoder().setApiKey(API_KEY);
        encoder = new HttpConformanceEncoder(props, mapper);
    }

    @AfterEach
    void stop() throws IOException, InterruptedException {
        socket.close();
        acceptor.join(2_000);
    }

    /** Accept one connection at a time (the client sends sequentially) until the socket closes. */
    private void serveLoop() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                handleOne(client);
            } catch (IOException e) {
                return; // socket closed by @AfterEach, or the exchange already failed the test
            }
        }
    }

    private void handleOne(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        String head = readHead(in);
        int contentLength = 0;
        for (String line : head.split("\r\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(
                        line.substring("content-length:".length()).trim());
            }
            if (lower.startsWith("authorization:")) {
                authHeaders.add(line.substring("authorization:".length()).trim());
            }
        }
        byte[] body = in.readNBytes(contentLength);
        requests.incrementAndGet();
        JsonNode request = mapper.readTree(new String(body, StandardCharsets.UTF_8));
        List<String> texts = new ArrayList<>();
        request.get("texts").forEach(t -> texts.add(t.asText()));

        Reply reply = handler.apply(texts);
        ObjectNode replyBody = reply.body();
        byte[] payload =
                replyBody == null ? "{}".getBytes(StandardCharsets.UTF_8) : mapper.writeValueAsBytes(replyBody);
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 " + reply.status() + " Stub\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + payload.length + "\r\n"
                        + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    /** Read up to and including the blank line ending the request head. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int c;
        while ((c = in.read()) != -1) {
            head.write(c);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && c == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = c;
        }
        return head.toString(StandardCharsets.UTF_8);
    }

    /** A well-formed response: one deterministic {@code dim}-wide vector per text. */
    private ObjectNode wellFormed(List<String> texts, String checkpoint, int dim) {
        ObjectNode body = mapper.createObjectNode();
        body.put("checkpoint", checkpoint);
        body.put("dim", dim);
        ArrayNode vectors = body.putArray("vectors");
        for (String text : texts) {
            ArrayNode vector = vectors.addArray();
            for (int j = 0; j < dim; j++) {
                vector.add(text.length() + j * 0.5);
            }
        }
        return body;
    }

    /** JUnit's message overloads want a non-null string; NullAway rightly flags getMessage(). */
    private static String message(Exception e) {
        return String.valueOf(e.getMessage());
    }

    @Test
    void happyPathReturnsVectorsInOrderWithBearerAuth() {
        handler = texts -> new Reply(200, wellFormed(texts, CHECKPOINT, 3));

        List<float[]> out = encoder.encode(CHECKPOINT, List.of("a", "bbb"));

        assertEquals(2, out.size());
        assertArrayEquals(new float[] {1f, 1.5f, 2f}, out.get(0), 1e-9f, "vector derived from 'a' (length 1)");
        assertArrayEquals(new float[] {3f, 3.5f, 4f}, out.get(1), 1e-9f, "vector derived from 'bbb' (length 3)");
        assertEquals(List.of("Bearer " + API_KEY), authHeaders, "one request, bearer-authed");
    }

    @Test
    void checkpointMismatchInResponseRefuses() {
        handler = texts -> new Reply(200, wellFormed(texts, "some/other-encoder", 3));

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> encoder.encode(CHECKPOINT, List.of("a")));
        assertTrue(message(e).contains("refusing"), message(e));
        assertTrue(message(e).contains("some/other-encoder"), message(e));
    }

    @Test
    void serviceErrorFailsLoudly() {
        handler = texts -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("error", "embedding failed");
            return new Reply(502, body);
        };

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> encoder.encode(CHECKPOINT, List.of("a")));
        assertTrue(message(e).contains("HTTP 502"), message(e));
    }

    @Test
    void unknownCheckpointRefusalFromServiceFailsLoudly() {
        handler = texts -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("error", "unknown embed checkpoint");
            return new Reply(400, body);
        };

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> encoder.encode(CHECKPOINT, List.of("a")));
        assertTrue(message(e).contains("HTTP 400"), message(e));
    }

    @Test
    void dimensionMismatchRefuses() {
        handler = texts -> {
            ObjectNode body = wellFormed(texts, CHECKPOINT, 3);
            ((ArrayNode) body.get("vectors").get(0)).add(9.9); // one vector wider than dim
            return new Reply(200, body);
        };

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> encoder.encode(CHECKPOINT, List.of("a")));
        assertTrue(message(e).contains("declared dim"), message(e));
    }

    @Test
    void shortVectorCountRefuses() {
        handler = texts -> {
            ObjectNode body = wellFormed(texts, CHECKPOINT, 3);
            ((ArrayNode) body.get("vectors")).remove(0);
            return new Reply(200, body);
        };

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> encoder.encode(CHECKPOINT, List.of("a", "b")));
        assertTrue(message(e).contains("1 vectors for 2 texts"), message(e));
    }

    @Test
    void nonNumericComponentRefuses() {
        handler = texts -> {
            ObjectNode body = wellFormed(texts, CHECKPOINT, 3);
            // What a NaN component looks like after Node's JSON.stringify: a null entry.
            ((ArrayNode) body.get("vectors").get(0)).set(1, NullNode.getInstance());
            return new Reply(200, body);
        };

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> encoder.encode(CHECKPOINT, List.of("a")));
        assertTrue(message(e).contains("non-numeric"), message(e));
    }

    @Test
    void unconfiguredUrlFailsBeforeAnyRequest() {
        ObserverProperties unconfigured = new ObserverProperties();
        HttpConformanceEncoder bare = new HttpConformanceEncoder(unconfigured, mapper);

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> bare.encode(CHECKPOINT, List.of("a")));
        assertTrue(message(e).contains("evals.observer.encoder.url"), message(e));
        assertEquals(0, requests.get(), "no request is attempted without a configured endpoint");
    }

    @Test
    void repeatTextsAreServedFromTheCacheNotTheService() {
        handler = texts -> new Reply(200, wellFormed(texts, CHECKPOINT, 3));

        List<float[]> first = encoder.encode(CHECKPOINT, List.of("same", "same", "other"));
        List<float[]> second = encoder.encode(CHECKPOINT, List.of("same", "other"));

        assertEquals(1, requests.get(), "duplicates within a call and repeat calls hit the cache");
        assertArrayEquals(first.get(0), first.get(1), "duplicate inputs share one embedding");
        assertArrayEquals(first.get(0), second.get(0));
        assertArrayEquals(first.get(2), second.get(1));
    }

    @Test
    void largeBatchesAreChunkedAndReassembledInOrder() {
        handler = texts -> new Reply(200, wellFormed(texts, CHECKPOINT, 2));

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            texts.add("t".repeat(i + 1)); // distinct lengths -> distinct vectors
        }
        List<float[]> out = encoder.encode(CHECKPOINT, texts);

        assertEquals(2, requests.get(), "33 texts split into 32 + 1 requests");
        assertEquals(33, out.size());
        for (int i = 0; i < 33; i++) {
            assertArrayEquals(new float[] {i + 1, i + 1.5f}, out.get(i), 1e-9f, "vector " + i + " is index-aligned");
        }
    }

    @Test
    void interChunkDimensionDriftRefuses() {
        handler = texts -> new Reply(200, wellFormed(texts, CHECKPOINT, requests.get() == 1 ? 2 : 3));

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            texts.add("u".repeat(i + 1));
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> encoder.encode(CHECKPOINT, texts));
        assertTrue(message(e).contains("dim"), message(e));
    }
}
