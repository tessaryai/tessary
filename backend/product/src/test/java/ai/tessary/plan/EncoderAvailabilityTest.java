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
import ai.tessary.testsupport.LoopbackHttpStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Status;

/**
 * {@link EncoderAvailability} against a loopback {@link ServerSocket} {@code GET /healthz} (forbidden-apis bans
 * {@code com.sun.net.httpserver}): each answer a probe lands on, as the health contributor and sweep gate read it.
 */
class EncoderAvailabilityTest {

    /** The shape {@code serve.py} answers {@code /healthz} with. */
    private static final String HEALTHY = "{\"ok\": true, \"heads\": [\"groundedness\"], \"device\": \"mps\"}";

    private LoopbackHttpStub stub;
    private ObserverProperties props;

    @AfterEach
    void stop() throws IOException {
        if (stub != null) stub.close();
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
        assertEquals(
                "GET /healthz HTTP/1.1",
                stub.requests().getFirst().line(),
                "the probe asks the service's own health path");
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
     * The model goes away by pointing at an open client connection's local port: nothing listens and the kernel keeps
     * the port. Closing the stub left it listening on Linux until its in-flight accept() returned.
     */
    @Test
    void anUnreachableServiceIsUnavailable() throws IOException {
        EncoderAvailability encoder = serve(200, HEALTHY);
        encoder.refresh();

        // The next probe flips, so sweeps pause.
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
    @ValueSource(strings = {"{\"ok\": true, \"heads\": []}", "{}", "{\"heads\": [\"sentiment\"]}", "<html>ok</html>"})
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

    /** A bad URL is logged, not thrown: a diagnostic must not stop the platform starting. */
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

    private EncoderAvailability serve(int status, String body) throws IOException {
        stub = LoopbackHttpStub.answering(status, body);
        props = new ObserverProperties();
        props.getEncoder().setUrl(stub.baseUrl());
        return new EncoderAvailability(props);
    }
}
