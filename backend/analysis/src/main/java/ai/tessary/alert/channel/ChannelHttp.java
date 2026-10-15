// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.ingest.BoundedBody;
import ai.tessary.ingest.UrlGuard;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Outbound POST for alert channels. {@link #post} re-runs {@link UrlGuard#requirePublicHttp} on every call because DNS
 * can rebind after a channel is created, sending a credentialed POST to IMDS or loopback. The response is read through
 * {@link BoundedBody} and never surfaced, since it could echo SSRF-probed internal content.
 */
public final class ChannelHttp {

    private static final HttpClient DEFAULT_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Test seam so connectors can hit a local stub while the SSRF guard still runs on the channel URL. Never set in
     * production.
     */
    private static volatile @org.jspecify.annotations.Nullable HttpClient clientOverride;

    private ChannelHttp() {}

    /** Pass null to reset. */
    public static void setClientForTest(@org.jspecify.annotations.Nullable HttpClient client) {
        clientOverride = client;
    }

    private static HttpClient client() {
        HttpClient override = clientOverride;
        return override != null ? override : DEFAULT_CLIENT;
    }

    /** {@code body} is already serialized JSON. */
    public static HttpResponse<String> post(String url, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        URI uri = UrlGuard.requirePublicHttp(url);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30));
        for (Map.Entry<String, String> h : headers.entrySet()) {
            b.header(h.getKey(), h.getValue());
        }
        return client().send(b.build(), BoundedBody.string());
    }
}
