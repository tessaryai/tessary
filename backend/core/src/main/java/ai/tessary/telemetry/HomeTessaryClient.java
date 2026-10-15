// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import ai.tessary.open.hash.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Thin JDK {@link HttpClient} transport to {@code home.tessary.ai}. Returns the status and never interprets the body;
 * the caller owns the enabled-gate, payload and error swallowing (see {@code TelemetryHeartbeat}).
 */
@Component
public class HomeTessaryClient {

    public static final String BASE_URL = "https://home.tessary.ai";

    private final HttpClient http;

    public HomeTessaryClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    HomeTessaryClient(HttpClient http) {
        this.http = http;
    }

    /** Throws on transport failure; the caller swallows, since telemetry must never fail its caller. */
    public int postJson(String path, String json) throws IOException, InterruptedException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // CloudFront signs requests to its Lambda origin without hashing a POST body, so the sender supplies
                // it; a missing or wrong hash gets 403.
                .header("x-amz-content-sha256", sha256Hex(body))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        return res.statusCode();
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(Sha256.digest(bytes));
    }

    /** The body is empty for anything but a 200. */
    public record Fetched(int status, byte[] body) {}

    /**
     * Throws past {@code maxBytes} rather than truncating, so a caller hashing the body can never mistake a cut-off
     * file. The caller checks the enabled-gate first.
     */
    public Fetched getBytes(String path, int maxBytes) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .GET()
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) return new Fetched(res.statusCode(), new byte[0]);
            byte[] body = in.readNBytes(maxBytes + 1);
            if (body.length > maxBytes) {
                throw new IOException(path + " is over the " + maxBytes + "-byte limit");
            }
            return new Fetched(res.statusCode(), body);
        }
    }
}
