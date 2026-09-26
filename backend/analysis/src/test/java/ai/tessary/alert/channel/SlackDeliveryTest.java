// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.SlackProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The backend's one route to Slack, against a real loopback stand-in for {@code slack-service}. The adapter
 * reports Slack's refusal in a 200 body, so the bugs are a 200 {@code ok:false} recorded as delivered, a
 * status or reason lost on the way into the delivery log, and a transport fault that throws out of the
 * fan-out instead of being returned as a failure.
 */
class SlackDeliveryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SlackProperties props = new SlackProperties();

    private ServerSocket adapter;
    private Thread acceptor;
    private volatile int answerStatus;
    private volatile String answerBody = "";
    private volatile @Nullable String seenAuth;
    private volatile @Nullable String seenPath;
    private volatile @Nullable String seenBody;

    /** A one-request-at-a-time HTTP/1.1 responder (forbidden-apis bans {@code com.sun.net.httpserver}). */
    @BeforeEach
    void startAdapter() throws IOException {
        adapter = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serve, "slack-adapter-stub");
        acceptor.setDaemon(true);
        acceptor.start();
        props.setBaseUrl("http://127.0.0.1:" + adapter.getLocalPort());
        props.setServiceKey("svc-key");
    }

    @AfterEach
    void stopAdapter() throws IOException, InterruptedException {
        Thread.interrupted(); // an interrupt a test left set would abort the join below
        adapter.close();
        acceptor.join(2_000);
    }

    private void serve() {
        while (!adapter.isClosed()) {
            try (Socket client = adapter.accept()) {
                InputStream in = client.getInputStream();
                String[] head = readHead(in).split("\r\n");
                seenPath = head[0].split(" ")[1];
                int contentLength = 0;
                for (String line : head) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(
                                line.substring("content-length:".length()).trim());
                    } else if (lower.startsWith("authorization:")) {
                        seenAuth = line.substring("authorization:".length()).trim();
                    }
                }
                seenBody = new String(in.readNBytes(contentLength), StandardCharsets.UTF_8);
                byte[] out = answerBody.getBytes(StandardCharsets.UTF_8);
                OutputStream os = client.getOutputStream();
                os.write(("HTTP/1.1 " + answerStatus + " X\r\nContent-Type: application/json\r\nContent-Length: "
                                + out.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.write(out);
                os.flush();
            } catch (IOException | RuntimeException e) {
                return; // closed by the test, or a client that hung up before sending a request line
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

    @ParameterizedTest(name = "HTTP {0} {1}")
    @MethodSource("answers")
    void theAdaptersAnswerBecomesTheDeliveryOutcome(int status, String body, DeliveryResult expected) throws Exception {
        answerStatus = status;
        answerBody = body;

        DeliveryResult result = new SlackDelivery(props, mapper)
                .sendToWebhook("https://hooks.slack.com/services/T/B/x", "*C-3* · Latency up 2x");

        assertEquals(expected, result);
        assertEquals("Bearer svc-key", seenAuth);
        assertEquals("/deliver", seenPath);
        assertEquals(
                mapper.readTree("{\"transport\":\"webhook\",\"url\":\"https://hooks.slack.com/services/T/B/x\","
                        + "\"text\":\"*C-3* · Latency up 2x\"}"),
                mapper.readTree(seenBody));
    }

    static Stream<Arguments> answers() {
        return Stream.of(
                Arguments.of(200, "{\"ok\":true,\"status\":201}", DeliveryResult.success(201)),
                Arguments.of(200, "{\"ok\":true}", DeliveryResult.success(200)),
                Arguments.of(200, "{\"ok\":true,\"status\":null}", DeliveryResult.success(200)),
                Arguments.of(
                        200,
                        "{\"ok\":false,\"error\":\"channel_not_found\",\"status\":404}",
                        DeliveryResult.failure(404, "channel_not_found")),
                Arguments.of(200, "{\"ok\":false}", DeliveryResult.failure(null, "slack refused the message")),
                Arguments.of(
                        200, "not json", DeliveryResult.failure(null, "slack-service returned an unreadable body")),
                Arguments.of(502, "", DeliveryResult.failure(502, "slack-service HTTP 502")));
    }

    @Test
    void anUnreachableAdapterIsAFailureNotAThrow() throws IOException {
        try (ServerSocket closed = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            props.setBaseUrl("http://127.0.0.1:" + closed.getLocalPort());
        }

        DeliveryResult result = new SlackDelivery(props, mapper).sendToWebhook("https://hooks.slack.com/x", "t");

        assertEquals(DeliveryResult.failure(null, "slack-service unreachable"), result);
    }

    @Test
    void anInterruptedSendIsReportedAndTheFlagRestored() {
        answerStatus = 200;
        answerBody = "{\"ok\":true}";
        Thread.currentThread().interrupt();

        DeliveryResult result = new SlackDelivery(props, mapper).sendToWebhook("https://hooks.slack.com/x", "t");

        assertEquals(DeliveryResult.failure(null, "interrupted"), result);
        assertEquals(true, Thread.currentThread().isInterrupted());
    }
}
