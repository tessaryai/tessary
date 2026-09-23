// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
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
}
