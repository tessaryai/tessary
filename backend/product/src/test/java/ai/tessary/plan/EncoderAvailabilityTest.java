// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.ObserverProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/**
 * {@link EncoderAvailability} against a loopback {@link ServerSocket} standing in for classify-service's
 * {@code GET /healthz} (forbidden-apis bans {@code com.sun.net.httpserver}, and the backend carries no
 * HTTP-mock dependency): the four answers a probe can land on, and that each is what the health
 * contributor and the capability layer read.
 */
class EncoderAvailabilityTest {

    private final List<String> requestLines = new ArrayList<>();
    private ServerSocket socket;
    private Thread acceptor;

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
    void a200FromHealthzIsAvailable() throws IOException {
        EncoderAvailability encoder = serve(200);

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertTrue(s.available(), s.reason());
        assertTrue(encoder.available());
        assertEquals(Status.UP, encoder.health().getStatus());
        assertEquals("GET /healthz HTTP/1.1", requestLines.getFirst(), "the probe asks the service's own health path");
    }

    @Test
    void anythingButA200IsUnavailableWithTheStatus() throws IOException {
        EncoderAvailability encoder = serve(503);

        EncoderAvailability.Snapshot s = encoder.refresh();

        assertFalse(s.available());
        assertEquals("healthz answered 503", s.reason());
        assertEquals(Status.UNKNOWN, encoder.health().getStatus());
        assertEquals("healthz answered 503", encoder.health().getDetails().get("reason"));
    }

    @Test
    void anUnreachableServiceIsUnavailableAndTheStateFollowsTheService() throws IOException {
        EncoderAvailability encoder = serve(200);
        assertTrue(encoder.refresh().available());

        // The service goes away: the next probe flips the answer, so an org that had the classifier
        // loses it rather than sweeping against nothing.
        socket.close();
        EncoderAvailability.Snapshot s = encoder.refresh();

        assertFalse(s.available());
        assertTrue(s.reason().startsWith("unreachable: "), s.reason());
    }

    /** A loopback listener answering every request with {@code status} and an empty JSON body. */
    private EncoderAvailability serve(int status) throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> serveLoop(status), "stub-healthz");
        acceptor.setDaemon(true);
        acceptor.start();
        ObserverProperties props = new ObserverProperties();
        props.getEncoder().setUrl("http://127.0.0.1:" + socket.getLocalPort());
        return new EncoderAvailability(props);
    }

    private void serveLoop(int status) {
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
                out.write(("HTTP/1.1 " + status + " Stub\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 2\r\nConnection: close\r\n\r\n{}")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                return;
            }
        }
    }
}
