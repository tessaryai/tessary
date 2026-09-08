// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.telemetry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Thin JDK-{@link HttpClient} seam for {@code home.tessary.ai}. Mirrors the house outbound-HTTP style
 * ({@code ingest/HttpJson}, {@code llm}/{@code WorkOsClient}, and the {@code MixpanelSender} this class
 * replaces): a shared client with a connect timeout and a per-request timeout, no third-party SDK.
 *
 * <p>Payloads are posted as raw JSON to whatever {@code path} the caller supplies — unlike
 * {@code MixpanelSender}'s two hardcoded endpoint constants, {@code path} is a parameter so
 * {@code TelemetryHeartbeat} (the heartbeat ping, §1 of the contract) and the epic-9 license-check
 * client (§2, not built by this issue) can share this one class untouched. This is a dumb transport: it
 * returns the HTTP status and never interprets the body. The caller owns the enabled-gate, payload
 * shape, and error swallowing — see {@code TelemetryHeartbeat}.
 */
@Component
public class HomeTessaryClient {

    private static final String BASE_URL = "https://home.tessary.ai";

    private final HttpClient http;

    public HomeTessaryClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    HomeTessaryClient(HttpClient http) {
        this.http = http;
    }

    /**
     * POST {@code json} to {@code path} (e.g. {@code "/ping"}) under {@value #BASE_URL}. Returns the
     * HTTP status code. Throws on transport failure; the caller swallows — telemetry must never fail a
     * caller's own operation.
     *
     * <p>Public, unlike the package-private {@code MixpanelSender.send} this class replaces: the caller
     * ({@code TelemetryHeartbeat}, §1) lives in a different module ({@code surfaces}) than this class
     * ({@code core}), and {@code path} being a parameter rather than a per-call constant is what lets
     * epic 9's future license-check client (§2) reuse this same class untouched.
     */
    public int postJson(String path, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        return res.statusCode();
    }
}
