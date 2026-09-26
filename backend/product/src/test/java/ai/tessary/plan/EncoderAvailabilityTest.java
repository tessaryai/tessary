// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.config.ObserverProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Status;

/**
 * {@link EncoderAvailability} against a loopback {@link ServerSocket} standing in for the model's
 * {@code GET /healthz} (forbidden-apis bans {@code com.sun.net.httpserver}, and the backend carries no
 * HTTP-mock dependency): the answers a probe can land on, and that each is what the health contributor
 * and the sweep gate read.
 */
class EncoderAvailabilityTest {

    /** The shape {@code serve.py} answers {@code /healthz} with. */
    private static final String HEALTHY = "{\"ok\": true, \"heads\": [\"groundedness\"], \"device\": \"mps\"}";

    private final List<String> requestLines = new ArrayList<>();
    private ServerSocket socket;
    private Thread acceptor;
    private ObserverProperties props;

    @AfterEach
    void stop() throws IOException, InterruptedException {
        if (socket != null) socket.close();
        if (acceptor != null) acceptor.join(2_000);
    }

    @Test
    void blankUrlIsNotConfiguredAndNeverTakesTheInstanceDown() {
        EncoderAvailability encoder = new EncoderAvailability(new ObserverProperties());

        assertFalse(encoder.available(), "unprobed reads as unavailable");
        assertEquals("not probed yet", encoder.snapshot().reason());

        EncoderAvailability.Snapshot s = encoder.refresh();
        assertFalse(s.available());
        assertEquals("tessary.observer.encoder.url is unset", s.reason());
        assertEquals(Status.UNKNOWN, encoder.health().getStatus());
        assertEquals(false, encoder.health().getDetails().get("configured"));
    }

    @Test
    void a200ListingTheGroundednessHeadIsAvailable() throws IOException {
        EncoderAvailability encoder = serve(200, HEALTHY);

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertTrue(s.available(), s.reason());
        assertTrue(encoder.available());
        assertEquals(Status.UP, encoder.health().getStatus());
        assertEquals("GET /healthz HTTP/1.1", requestLines.getFirst(), "the probe asks the service's own health path");
    }

    @Test
    void a200WithoutTheGroundednessHeadIsUnavailable() throws IOException {
        // A server with no groundedness model loaded: healthy, but not this model.
        EncoderAvailability encoder = serve(200, "{\"ok\": true, \"heads\": []}");

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertFalse(s.available());
        assertEquals("healthz answered 200 without the groundedness head", s.reason());
    }

    @Test
    void a200WithNoHeadsAtAllIsUnavailable() throws IOException {
        EncoderAvailability encoder = serve(200, "{}");

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertFalse(s.available());
        assertEquals("healthz answered 200 without the groundedness head", s.reason());
    }

    @Test
    void anythingButA200IsUnavailableWithTheStatus() throws IOException {
        EncoderAvailability encoder = serve(503, HEALTHY);

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertFalse(s.available());
        assertEquals("healthz answered 503", s.reason());
        assertEquals(Status.UNKNOWN, encoder.health().getStatus());
        assertEquals("healthz answered 503", encoder.health().getDetails().get("reason"));
    }

    /**
     * The model goes away by moving the URL to the local port of an open loopback client connection:
     * nothing listens there, so the connect is refused, and while the connection holds the port the
     * kernel hands it to no one else. Closing the stub's socket instead left it listening on Linux until
     * the stub's in-flight {@code accept()} returned, so the next probe could still be answered 200.
     */
    @Test
    void anUnreachableServiceIsUnavailable() throws IOException {
        EncoderAvailability encoder = serve(200, HEALTHY);
        encoder.refresh();

        // The model goes away: the next probe flips the answer, so sweeps pause rather than run
        // against nothing.
        EncoderAvailability.Snapshot s;
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket held = new Socket(peer.getInetAddress(), peer.getLocalPort())) {
            props.getEncoder().setUrl("http://127.0.0.1:" + held.getLocalPort());
            s = encoder.refresh();
        }

        assertFalse(s.available());
        assertTrue(s.reason().startsWith("unreachable: "), s.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"heads\": [\"sentiment\"]}", "<html>ok</html>"})
    void a200ThatDoesNotListTheHeadIsUnavailable(String body) throws IOException {
        EncoderAvailability encoder = serve(200, body);

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertFalse(s.available(), "another model's heads, or a body that is not JSON, is not this model");
        assertEquals("healthz answered 200 without the groundedness head", s.reason());
    }

    @Test
    void aUrlThatIsNotAUrlIsUnavailableWithThatReason() {
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("http://bad host:8000");

        EncoderAvailability.Snapshot s = new EncoderAvailability(props).refresh();

        assertFalse(s.available());
        assertEquals("tessary.observer.encoder.url is not a URL", s.reason());
    }

    /** A diagnostic that can stop the platform starting is the worse bug: a bad URL is logged, not thrown. */
    @Test
    void aBootProbeThatCannotEvenBuildItsRequestNeverFailsStartup() {
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("ftp://encoder.internal");
        EncoderAvailability encoder = new EncoderAvailability(props);

        assertDoesNotThrow(encoder::probeOnBoot);

        assertFalse(encoder.available());
        assertEquals("not probed yet", encoder.snapshot().reason());
    }

    @SuppressWarnings("unchecked")
    @Test
    void anInterruptedProbeIsUnavailableAndKeepsTheInterrupt() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException());
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("http://127.0.0.1:8000");

        EncoderAvailability.Snapshot s;
        boolean interrupted;
        try {
            s = new EncoderAvailability(props, client).refresh();
        } finally {
            interrupted = Thread.interrupted();
        }

        assertFalse(s.available());
        assertEquals("probe interrupted", s.reason());
        assertTrue(interrupted, "the scheduler's shutdown request must survive the probe");
    }

    /** A loopback listener answering every request with {@code status} and {@code body}. */
    private EncoderAvailability serve(int status, String body) throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> serveLoop(status, body), "stub-healthz");
        acceptor.setDaemon(true);
        acceptor.start();
        props = new ObserverProperties();
        props.getEncoder().setUrl("http://127.0.0.1:" + socket.getLocalPort());
        return new EncoderAvailability(props);
    }

    private void serveLoop(int status, String body) {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                InputStream in = client.getInputStream();
                StringBuilder head = new StringBuilder();
                int c;
                while ((c = in.read()) != -1) {
                    head.append((char) c);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                requestLines.add(head.toString().split("\r\n")[0]);
                OutputStream out = client.getOutputStream();
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 " + status + " Stub\r\nContent-Type: application/json\r\n" + "Content-Length: "
                                + payload.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                return;
            }
        }
    }
}
