// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link RateLimitFilter#shouldNotFilter}'s posture table, on the {@code AuthFilterPostureTest}
 * precedent (#935): plain JUnit, no Spring context, no Docker. This filter needs neither — it
 * reads a request path and, on the throttled path, an in-memory map, so it runs everywhere and
 * runs fast.
 *
 * <p>Written new because no {@code RateLimitFilterTest} existed anywhere in the repo before #935,
 * unlike {@link AuthFilter}, which had a thorough posture test. The gap mattered here specifically:
 * the fix this file backs (widening {@code shouldNotFilter} to cover guarded actuator paths) had no
 * regression harness to extend, and no established shape to model a new one after other than this
 * filter's own doc comment.
 */
class RateLimitFilterTest {

    private static RateLimitFilter filter() {
        return new RateLimitFilter(new ObjectMapper());
    }

    /**
     * {@code shouldNotFilter} reads {@code getServletPath()} first, falling back to
     * {@code getRequestURI()} only when the former is {@code null} — not when it's empty. A bare
     * {@link MockHttpServletRequest} defaults {@code getServletPath()} to {@code ""} rather than
     * {@code null}, so without this, every path in this file would silently resolve to the empty
     * string and every {@code startsWith} check would miss. A real servlet container always
     * populates the servlet path, so this is a test-fixture concern, not a production one.
     */
    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        return req;
    }

    @Test
    @DisplayName("/api/** and /mcp remain rate-limited")
    void apiAndMcpAreRateLimited() {
        assertFalse(
                filter().shouldNotFilter(request("GET", "/api/orgs/acme/projects/web/traces")),
                "/api/** must stay rate-limited");
        assertFalse(filter().shouldNotFilter(request("POST", "/mcp")), "/mcp must stay rate-limited");
    }

    @Test
    @DisplayName("the three public health probes remain exempt -- the load-balancer path")
    void publicHealthProbesAreExempt() {
        for (String path :
                new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertTrue(
                    filter().shouldNotFilter(request("GET", path)),
                    path + " is polled by orchestrators before anything holds a credential and must stay exempt");
        }
    }

    @Test
    @DisplayName("a guarded actuator path is NOT exempt (#935) -- it now carries a staff-verified ctx")
    void guardedActuatorPathsAreRateLimited() {
        for (String path : new String[] {
            "/actuator", "/actuator/env", "/actuator/loggers", "/actuator/heapdump", "/actuator/prometheus"
        }) {
            assertFalse(
                    filter().shouldNotFilter(request("GET", path)),
                    path + " must be rate-limited: AuthFilter runs first (@Order(10) vs this filter's "
                            + "@Order(20)) and now requires PlatformStaff.isStaff for this path, so anything "
                            + "reaching here carries a real principal, same as /api/** and /mcp");
        }
    }

    @Test
    @DisplayName("a health-prefixed sibling is not exempt by name")
    void healthPrefixedSiblingIsNotExempt() {
        assertFalse(
                filter().shouldNotFilter(request("GET", "/actuator/healthz")),
                "/actuator/health is matched exactly by isPublicActuatorPath; a same-prefix sibling must not "
                        + "inherit the exemption");
    }

    // -----------------------------------------------------------------------------------------
    // #852: POST /auth/signup and /auth/login are the two credential-checking routes the
    // dependency-free password provider adds. They must now be rate-limited by IP, since they
    // have no TenantContext to key on -- and every OTHER /auth/** path must stay exempt exactly
    // as before.
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /auth/signup and /auth/login are no longer exempt (#852)")
    void credentialRoutesAreNotExempt() {
        assertFalse(filter().shouldNotFilter(request("POST", "/auth/signup")), "/auth/signup must be rate-limited");
        assertFalse(filter().shouldNotFilter(request("POST", "/auth/login")), "/auth/login must be rate-limited");
    }

    @Test
    @DisplayName("GET /auth/login (the OAuth redirect) stays exempt -- same path, different method")
    void oauthLoginGetStaysExempt() {
        assertTrue(
                filter().shouldNotFilter(request("GET", "/auth/login")),
                "the OAuth GET dance shares a path with the new POST credential route but must not be "
                        + "swept into rate limiting by it");
    }

    @Test
    @DisplayName("every other /auth/** path stays exempt")
    void otherAuthPathsStayExempt() {
        for (String path : new String[] {"/auth/callback", "/auth/logout", "/auth/me", "/auth/link/start"}) {
            assertTrue(filter().shouldNotFilter(request("GET", path)), path + " must remain exempt");
        }
        assertTrue(filter().shouldNotFilter(request("POST", "/auth/logout")), "/auth/logout must remain exempt");
    }

    @Test
    @DisplayName("an unauthenticated burst against POST /auth/login from one IP is throttled")
    void credentialRouteBurstIsThrottled() throws ServletException, IOException {
        RateLimitFilter f = filter();
        int rejected = 0;
        // Burst capacity is 5; six rapid requests from the same IP must trip the limiter.
        for (int i = 0; i < 6; i++) {
            MockHttpServletRequest req = request("POST", "/auth/login");
            req.setRemoteAddr("203.0.113.7");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            f.doFilterInternal(req, res, chain);
            if (res.getStatus() == 429) rejected++;
        }
        assertTrue(rejected >= 1, "at least one of six rapid unauthenticated logins from one IP must 429");
    }

    @Test
    @DisplayName("a full pool evicts the coldest buckets and keeps the ones being spent")
    void fullPoolEvictsByAgeRatherThanLockingOutNewCallers() throws Exception {
        RateLimitFilter f = filter();

        // Both of these exhaust their burst, so either one still holding its bucket answers 429 and
        // either one whose bucket was evicted answers 200 on a fresh full bucket. That difference is
        // the whole assertion: it distinguishes evicted from retained through behaviour alone, which
        // an "is the new address admitted" check cannot — computeIfAbsent never refuses on size, so
        // that check passes whether or not a single byte was ever reclaimed.
        String cold = "203.0.113.10";
        for (int i = 0; i < 6; i++) login(f, cold);
        assertEquals(429, login(f, cold).getStatus(), "the cold caller starts out at its limit");

        // Fill past the cap with addresses that are all being SPENT, so the idle sweep can free
        // nothing: a bucket needs 25s to refill its burst and gets nowhere near that here. This is the
        // shape a caller minting addresses out of an IPv6 /64 produces, and the reason a full pool must
        // evict rather than refuse — refusing would turn every later sign-in from an address not
        // already in the map away for as long as that caller cared to continue.
        for (int i = 0; i < 10_050; i++) {
            login(f, "198.51.100." + (i / 250) + "." + (i % 250));
        }
        // Past the reclaim interval, so the next new key actually runs a pass instead of returning
        // early. Without this the fill completes inside one interval and the eviction arm is never
        // entered at all.
        Thread.sleep(1_100);

        // Establishing this caller is the first new key after the interval, so it is what triggers the
        // pass — and reclaim runs BEFORE the insert, so this bucket cannot be evicted by the pass it
        // caused. It then spends its burst, giving it the freshest stamp in the pool.
        String hot = "203.0.113.20";
        for (int i = 0; i < 6; i++) login(f, hot);

        assertEquals(
                429,
                login(f, hot).getStatus(),
                "a caller currently being throttled holds the freshest stamp and must survive the pass");
        assertEquals(
                200,
                login(f, cold).getStatus(),
                "the coldest bucket must have been evicted — a retained one would still be at its limit");
        assertEquals(
                200,
                login(f, "203.0.113.21").getStatus(),
                "and an address seen for the first time is served rather than locked out");
    }

    @Test
    @DisplayName("Retry-After carries the bucket's own refill, not a flat second")
    void retryAfterMatchesTheCredentialRefill() throws ServletException, IOException {
        RateLimitFilter f = filter();
        String ip = "203.0.113.30";
        MockHttpServletResponse limited = login(f, ip);
        for (int i = 0; i < 8 && limited.getStatus() != 429; i++) {
            limited = login(f, ip);
        }
        assertEquals(429, limited.getStatus(), "the burst must trip the limiter");
        // The credential pool refills one token every five seconds; "1" would be an instruction to
        // collect four more 429s.
        assertEquals("5", limited.getHeader("Retry-After"));
    }

    private static MockHttpServletResponse login(RateLimitFilter f, String remoteAddr)
            throws ServletException, IOException {
        MockHttpServletRequest req = request("POST", "/auth/login");
        req.setRemoteAddr(remoteAddr);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilterInternal(req, res, new MockFilterChain());
        return res;
    }

    @Test
    @DisplayName("/auth/callback, /auth/me, /auth/logout stay unthrottled even with no TenantContext")
    void otherAuthRoutesRemainUnthrottled() throws ServletException, IOException {
        RateLimitFilter f = filter();
        for (String path : new String[] {"/auth/callback", "/auth/me", "/auth/logout"}) {
            for (int i = 0; i < 20; i++) {
                MockHttpServletRequest req = request("GET", path);
                req.setRemoteAddr("203.0.113.8");
                MockHttpServletResponse res = new MockHttpServletResponse();
                MockFilterChain chain = new MockFilterChain();
                f.doFilterInternal(req, res, chain);
                assertEquals(200, res.getStatus(), path + " must never be throttled by this filter");
            }
        }
    }
}
