// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The reserved IPv4 ranges {@link UrlGuard} must refuse on top of the {@link java.net.InetAddress}
 * predicates, which cover none of them. A webhook or media URL pointing into one of these ranges would be
 * fetched server-side: SSRF. IP literals only, so no test does a DNS lookup.
 */
class UrlGuardTest {

    /**
     * 100.64.0.0/10 (carrier-grade NAT, the metadata range on some clouds) at both ends, 0.0.0.0/8 beyond
     * the any-local address, 198.18.0.0/15 (benchmarking) and 192.0.0.0/24 (IETF protocol assignments).
     */
    @ParameterizedTest
    @ValueSource(strings = {"100.64.0.1", "100.127.255.254", "0.0.0.1", "198.18.0.1", "192.0.0.1"})
    void refusesAReservedIpv4Host(String host) {
        TessaryException e =
                assertThrows(TessaryException.class, () -> UrlGuard.requirePublicHttp("https://" + host + "/hook"));
        assertEquals(IngestError.INVALID_BASE_URL, e.error());
    }

    /** Just past the CGNAT block, and a documentation address: public as far as the guard can tell. */
    @ParameterizedTest
    @ValueSource(strings = {"100.128.0.1", "203.0.113.5"})
    void acceptsAPublicIpv4Host(String host) {
        assertEquals(URI.create("https://" + host + "/hook"), UrlGuard.requirePublicHttp("https://" + host + "/hook"));
    }

    /**
     * Everything short of a public http(s) host is refused before any fetch: no URL, one that does not
     * parse, a non-http scheme or none, no host, credentials in the authority, a host that does not
     * resolve, and each private or internal address family. The unresolvable host is an IPv6 literal with a
     * scope that names no interface, so it fails without a DNS lookup.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "http://exa mple.com",
                "ftp://203.0.113.5/",
                "example.com",
                "http:///path",
                "http://user@203.0.113.5/",
                "http://[fe80::1%nosuchif0]/",
                "http://127.0.0.1/",
                "http://169.254.169.254/latest/meta-data",
                "http://10.0.0.1/",
                "http://0.0.0.0/",
                "http://224.0.0.1/",
                "http://[fc00::1]/"
            })
    void refusesAnythingButAPublicHttpUrl(String url) {
        TessaryException e = assertThrows(TessaryException.class, () -> UrlGuard.requirePublicHttp(url));
        assertEquals(IngestError.INVALID_BASE_URL, e.error());
    }

    /** The scheme check is case-insensitive, and a public IPv6 host is not mistaken for a reserved IPv4 one. */
    @ParameterizedTest
    @ValueSource(strings = {"HTTPS://203.0.113.5/hook", "http://[2001:db8::1]/hook"})
    void acceptsAPublicHttpUrlInAnySpelling(String url) {
        assertEquals(URI.create(url), UrlGuard.requirePublicHttp(url));
    }
}
