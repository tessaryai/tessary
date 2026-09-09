// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF guard for user-supplied ingestion-source URLs. The ingestion pipeline
 * fetches these URLs server-side with attached credentials, so any pointer at
 * an internal host would either leak credentials onto the internal network or
 * pull a response back to the user that the user wouldn't otherwise see (EC2
 * IMDS, localhost services, RFC1918 neighbours on the docker network).
 *
 * <p>Rules:
 * <ul>
 *   <li>scheme must be {@code http} or {@code https} (no {@code file:},
 *       {@code gopher:}, {@code jar:}, etc.)</li>
 *   <li>host must be present</li>
 *   <li>after DNS resolution, no resolved address may be loopback, link-local
 *       (covers IMDS 169.254.169.254), site-local (RFC1918), any-local
 *       (0.0.0.0), or multicast</li>
 * </ul>
 *
 * <p>The DNS resolution happens at validation time, which means an attacker
 * could in theory bypass this with a DNS-rebinding attack (resolve to a public
 * IP now, an internal IP at fetch time). Mitigation here is upstream: the
 * SourceService persists the URL string; every fetch goes through the same
 * guard, so a rebinding-victim fetch will fail at the next call. For a
 * stronger guarantee we'd resolve once and pin the IP in the HttpClient — out
 * of scope for now.</p>
 */
public final class UrlGuard {

    private UrlGuard() {}

    public static URI requirePublicHttp(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new TessaryException(IngestError.INVALID_BASE_URL, "must be non-empty");
        }
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new TessaryException(IngestError.INVALID_BASE_URL, e, "malformed URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new TessaryException(IngestError.INVALID_BASE_URL, "scheme must be http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new TessaryException(IngestError.INVALID_BASE_URL, "host missing");
        }
        // Block obvious bypasses like userinfo `http://evil@169.254.169.254/`.
        // URI parsing already separates these, but reject userinfo entirely to
        // keep audit/logging clean.
        if (uri.getUserInfo() != null) {
            throw new TessaryException(IngestError.INVALID_BASE_URL, "userinfo not allowed");
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new TessaryException(IngestError.INVALID_BASE_URL, e, "host did not resolve");
        }
        for (InetAddress a : addrs) {
            if (a.isLoopbackAddress()
                    || a.isLinkLocalAddress() // covers 169.254.0.0/16 (IMDS) and fe80::/10
                    || a.isSiteLocalAddress() // RFC1918 (IPv4 only — see below for IPv6)
                    || a.isAnyLocalAddress() // 0.0.0.0 / ::
                    || a.isMulticastAddress()
                    || isUniqueLocalIpv6(a)
                    || isReservedIpv4(a)) {
                throw new TessaryException(IngestError.INVALID_BASE_URL, "host resolves to a private/internal address");
            }
        }
        return uri;
    }

    /** RFC 4193 fc00::/7 — InetAddress doesn't expose this directly. */
    private static boolean isUniqueLocalIpv6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /**
     * IPv4 ranges unsafe to fetch server-side that the {@link InetAddress} predicates
     * above do not cover: RFC 6598 carrier-grade NAT (100.64.0.0/10 — used as the
     * link-local / metadata range on some clouds), RFC 5735 "this host on this network"
     * (0.0.0.0/8 beyond the single any-local address), RFC 2544 benchmarking
     * (198.18.0.0/15), and RFC 7335 / IETF protocol assignments (192.0.0.0/24).
     */
    private static boolean isReservedIpv4(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 4) return false;
        int o0 = b[0] & 0xff;
        int o1 = b[1] & 0xff;
        return o0 == 0 // 0.0.0.0/8
                || (o0 == 100 && (o1 & 0xc0) == 0x40) // 100.64.0.0/10 (CGNAT)
                || (o0 == 198 && (o1 & 0xfe) == 18) // 198.18.0.0/15 (benchmarking)
                || (o0 == 192 && o1 == 0 && (b[2] & 0xff) == 0); // 192.0.0.0/24 (IETF protocol assignments)
    }
}
