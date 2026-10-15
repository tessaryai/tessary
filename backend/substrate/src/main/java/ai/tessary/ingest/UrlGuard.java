// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF guard for user-supplied ingestion URLs, fetched server-side with credentials. Only {@code http}/{@code https}, a
 * host, and no resolved address that is loopback, link-local, site-local, any-local, multicast or reserved. DNS
 * rebinding is mitigated only by re-checking on every fetch; pinning the IP is out of scope.
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
        // Reject userinfo (http://evil@169.254.169.254/) entirely to keep audit and logging clean.
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
     * Ranges the {@link InetAddress} predicates miss: CGNAT 100.64.0.0/10 (metadata on some clouds), 0.0.0.0/8,
     * 198.18.0.0/15 and 192.0.0.0/24.
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
