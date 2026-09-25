// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.ObserverProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
        assertEquals(s.checkedAt(), s.lastAvailableAt());
        assertEquals(Status.UP, encoder.health().getStatus());
        assertEquals("GET /healthz HTTP/1.1", requestLines.getFirst(), "the probe asks the service's own health path");
    }

    @Test
    void a200WithoutTheGroundednessHeadIsUnavailable() throws IOException {
        // What classify-service answers with an empty model manifest: healthy, but not this model.
        EncoderAvailability encoder = serve(200, "{\"ok\": true, \"heads\": []}");

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertFalse(s.available());
        assertEquals("healthz answered 200 without the groundedness head", s.reason());
        assertNull(s.lastAvailableAt());
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
    void anUnreachableServiceIsUnavailableAndKeepsWhenItWasLastUp() throws IOException {
        EncoderAvailability encoder = serve(200, HEALTHY);
        Instant up = encoder.refresh().checkedAt();

        // The model goes away: the next probe flips the answer, so sweeps pause rather than run
        // against nothing, and the last time it answered survives for the status read.
        EncoderAvailability.Snapshot s;
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket held = new Socket(peer.getInetAddress(), peer.getLocalPort())) {
            props.getEncoder().setUrl("http://127.0.0.1:" + held.getLocalPort());
            s = encoder.refresh();
        }

        assertFalse(s.available());
        assertTrue(s.reason().startsWith("unreachable: "), s.reason());
        assertEquals(up, s.lastAvailableAt());
    }

    @Test
    void markUnreachableFlipsItDownAtOnceAndTheNextProbeFlipsItBack() throws IOException {
        EncoderAvailability encoder = serve(200, HEALTHY);
        Instant up = encoder.refresh().checkedAt();

        encoder.markUnreachable("unreachable: ConnectException");

        assertFalse(encoder.available());
        assertEquals("unreachable: ConnectException", encoder.snapshot().reason());
        assertEquals(up, encoder.snapshot().lastAvailableAt());

        assertTrue(encoder.refresh().available(), "a probe that finds it up again lifts the mark");
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
