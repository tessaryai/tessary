// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.testsupport.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The three properties {@link VerifiedTokenCache} is only worth having if it holds. Each of these was
 * asked for in review after the 590-test suite passed green over a real authentication bypass, so each
 * one names the failure it exists to catch rather than the method it calls.
 */
@SpringBootTest
class VerifiedTokenCacheTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService tokens;

    @Autowired
    VerifiedTokenCache cache;

    @Autowired
    TokenCacheProperties props;

    /**
     * Project deletion revokes a whole project's keys in one UPDATE, straight at the repository and never
     * through {@link ApiKeyService}. Before the fix the cache never heard about it, so a deleted
     * project's keys kept authenticating ingest for a full TTL — the exact window the synchronous bulk
     * revoke exists to close.
     */
    @Test
    void projectDeletion_stopsCachedKeysVerifying() {
        var fix = TenantFixture.bootstrap(tenants, "cache-project-delete");
        var issued = tokens.issue(fix.project().id(), fix.user().id(), "ingest");

        assertTrue(tokens.verify(issued.plaintext()).isPresent(), "live key verifies");
        assertInstanceOf(
                VerifiedTokenCache.Lookup.Verified.class,
                cache.lookup(issued.plaintext()),
                "and is now cached, which is what makes the next assertion meaningful");

        tenants.deleteProjectAsync(fix.project().id(), java.time.Instant.now().toString());

        assertTrue(
                tokens.verify(issued.plaintext()).isEmpty(),
                "a key revoked by project deletion must stop verifying immediately, not after the TTL");
    }

    /**
     * An invalidation that lands while a verification is in flight must win. The in-flight request read a
     * live row before the revoke and would otherwise store it afterwards, re-caching a credential the
     * operator had just withdrawn. The generation read before the row read is what detects that.
     */
    @Test
    void invalidationDuringVerification_isNotOverwritten() {
        var fix = TenantFixture.bootstrap(tenants, "cache-race");
        var issued = tokens.issue(fix.project().id(), fix.user().id(), "racer");
        var key = issued.token();

        // Stand in for a verification that read the row, then lost the CPU: capture the generation, let a
        // revocation land, then try to publish the result the way verify() would.
        long observed = cache.generation();
        cache.invalidate(key.id());
        cache.rememberVerified(issued.plaintext(), key, observed);

        assertInstanceOf(
                VerifiedTokenCache.Lookup.Unknown.class,
                cache.lookup(issued.plaintext()),
                "a result derived from a read that predates the invalidation must not be cached");
    }

    /**
     * The whole justification for two maps rather than one: an anonymous flood of invented tokens fills
     * the rejection map and must not evict a verified entry. With a single map this test fails and the
     * cache stops doing its job exactly when the instance is under load.
     */
    @Test
    void floodOfInvalidTokens_doesNotEvictVerifiedEntries() {
        var fix = TenantFixture.bootstrap(tenants, "cache-flood");
        var issued = tokens.issue(fix.project().id(), fix.user().id(), "genuine");

        assertTrue(tokens.verify(issued.plaintext()).isPresent());
        assertInstanceOf(VerifiedTokenCache.Lookup.Verified.class, cache.lookup(issued.plaintext()));

        // Comfortably more rubbish than either cap holds, so eviction is certainly exercised.
        int flood = props.getMaxRejections() * 3;
        for (int i = 0; i < flood; i++) {
            cache.rememberRejected(ApiKeyService.TOKEN_PREFIX + "w_floodfloodflood" + i);
        }

        assertInstanceOf(
                VerifiedTokenCache.Lookup.Verified.class,
                cache.lookup(issued.plaintext()),
                "rejections must evict only rejections — a verified entry survives any volume of them");
        assertTrue(tokens.verify(issued.plaintext()).isPresent(), "and the token still authenticates");
    }

    /** The kill switch has to mean it: with the cache off, nothing is remembered on any path. */
    @Test
    void disabled_remembersNothing() {
        var fix = TenantFixture.bootstrap(tenants, "cache-off");
        var issued = tokens.issue(fix.project().id(), fix.user().id(), "off");
        boolean was = props.isEnabled();
        try {
            props.setEnabled(false);
            assertTrue(tokens.verify(issued.plaintext()).isPresent(), "verification still works");
            assertInstanceOf(
                    VerifiedTokenCache.Lookup.Unknown.class,
                    cache.lookup(issued.plaintext()),
                    "but nothing is cached, so behaviour is the pre-cache behaviour");
            assertFalse(tokens.verify(issued.plaintext() + "x").isPresent(), "and a wrong token still fails");
        } finally {
            props.setEnabled(was);
        }
    }

    /** A revoked key must not be answered for, and the rejection must not be cached as a false positive. */
    @Test
    void revokedKey_isRejectedAndStaysRejected() {
        var fix = TenantFixture.bootstrap(tenants, "cache-revoke");
        var issued = tokens.issue(fix.project().id(), fix.user().id(), "revoked");
        assertTrue(tokens.verify(issued.plaintext()).isPresent());

        assertTrue(tokens.revoke(issued.token().id()));

        assertTrue(tokens.verify(issued.plaintext()).isEmpty(), "revoked immediately");
        assertEquals(
                0,
                tokens.verify(issued.plaintext()).stream().count(),
                "and on the repeat, which is served from the rejection memory");
    }
}
