// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert.channel;

import ai.tessary.evals.ingest.BoundedBody;
import ai.tessary.evals.ingest.UrlGuard;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Outbound POST helper for alert channels. A thin wrapper over the shared {@code ingest/UrlGuard}
 * SSRF guard and {@code ingest/BoundedBody} size-capped reader.
 *
 * <p>{@link #post} re-runs {@link UrlGuard#requirePublicHttp} on EVERY call. Channel URLs are
 * validated when the channel is created, but DNS can rebind between then and a send — without this
 * re-check a host that flips to 169.254.169.254 (EC2 IMDS) or 127.0.0.1 would receive a credentialed
 * POST. Connector endpoints (Sentry/Linear/PagerDuty) are public hosts and pass the guard; the guard's
 * real job is the user-supplied webhook/Slack URLs. The response body is read through
 * {@link BoundedBody} so a hostile upstream can never OOM the box, and is never surfaced to the caller
 * (it could echo SSRF-probed internal content).
 */
public final class ChannelHttp {

    private static final HttpClient DEFAULT_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Test seam: when set, sends route through this client instead of {@link #DEFAULT_CLIENT}. Lets a
     * test drive every connector against a real local stub server while the SSRF guard still runs for
     * real on the (public TEST-NET) channel URL. Never set in production.
     */
    private static volatile @org.jspecify.annotations.Nullable HttpClient clientOverride;

    private ChannelHttp() {}

    /** Install a test HttpClient (e.g. one that redirects to a loopback stub). Pass null to reset. */
    public static void setClientForTest(@org.jspecify.annotations.Nullable HttpClient client) {
        clientOverride = client;
    }

    private static HttpClient client() {
        HttpClient override = clientOverride;
        return override != null ? override : DEFAULT_CLIENT;
    }

    /** POST {@code body} (already serialized JSON) to {@code url} with the given headers. */
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
