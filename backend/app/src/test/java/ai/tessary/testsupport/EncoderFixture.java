// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.config.ObserverProperties;
import ai.tessary.plan.EncoderAvailability;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Makes the instance's encoder answer, for encoder-backed classifier tests. {@code groundedness} sweeps only while
 * its model answers the probe, so a test expecting a sweep would otherwise test the pause. This stands up a loopback
 * listener answering {@code 200} with the head, points the shared {@link ObserverProperties} at it, and re-probes.
 *
 * <p>The properties bean is shared: pair every {@link #up()} with {@link #down()} in {@code @AfterEach}.
 */
@Component
public class EncoderFixture {

    /** The shape {@code serve.py} answers {@code /healthz} with. */
    private static final String HEALTHY = "{\"ok\":true,\"heads\":[\"groundedness\"]}";

    private final ObserverProperties props;
    private final EncoderAvailability encoder;
    private @Nullable ServerSocket socket;
    private @Nullable Thread acceptor;
    private String previousUrl = "";

    public EncoderFixture(ObserverProperties props, EncoderAvailability encoder) {
        this.props = props;
        this.encoder = encoder;
    }

    /** Idempotent. */
    public synchronized void up() {
        if (socket != null) return;
        try {
            socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("could not open the stub healthz listener", e);
        }
        acceptor = new Thread(this::serveLoop, "stub-healthz");
        acceptor.setDaemon(true);
        acceptor.start();
        previousUrl = props.getEncoder().getUrl();
        props.getEncoder().setUrl("http://127.0.0.1:" + socket.getLocalPort());
        EncoderAvailability.Snapshot s = encoder.refresh();
        if (!s.available()) throw new IllegalStateException("stub encoder did not come up: " + s.reason());
    }

    /** Back to unprobed and unavailable. Idempotent. */
    public synchronized void down() {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing a loopback listener; nothing to recover
        }
        socket = null;
        props.getEncoder().setUrl(previousUrl);
        encoder.refresh();
    }

    private void serveLoop() {
        ServerSocket listening = socket;
        while (listening != null && !listening.isClosed()) {
            try (Socket client = listening.accept()) {
                InputStream in = client.getInputStream();
                StringBuilder head = new StringBuilder();
                int c;
                while ((c = in.read()) != -1) {
                    head.append((char) c);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                OutputStream out = client.getOutputStream();
                byte[] body = HEALTHY.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 Stub\r\nContent-Type: application/json\r\n" + "Content-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                return;
            }
        }
    }
}
