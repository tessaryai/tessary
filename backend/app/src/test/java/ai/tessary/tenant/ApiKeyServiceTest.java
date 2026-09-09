// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.testsupport.TenantFixture;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MCP-token semantics are security-critical: a bcrypt regression, a missed
 * revocation, or an off-by-one in the prefix length all turn into "every
 * stale token works forever". Coverage focuses on the verification surface.
 */
@SpringBootTest
class ApiKeyServiceTest {

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService tokens;

    @Autowired
    ApiKeyRepository repo;

    @Test
    void issuedToken_verifies_andUpdatesLastUsedAt() throws Exception {
        var fix = TenantFixture.bootstrap(tenants, "tok-verify");
        var issued = tokens.issue(fix.project().id(), fix.user().id(), "laptop");

        assertNotNull(issued.plaintext());
        assertTrue(issued.plaintext().startsWith(ApiKeyService.TOKEN_PREFIX));
        assertEquals(ApiKeyService.PREFIX_LEN, issued.token().tokenPrefix().length());
        assertNull(issued.token().lastUsedAt(), "fresh token has no last_used_at");

        Optional<ApiKey> v = tokens.verify(issued.plaintext());
        assertTrue(v.isPresent());
        assertEquals(issued.token().id(), v.get().id());

        // Give the async write a beat — currently synchronous but be defensive.
        Thread.sleep(20);
        ApiKey refreshed = repo.findById(issued.token().id()).orElseThrow();
        assertNotNull(refreshed.lastUsedAt(), "verify() must tick last_used_at on success");
    }

    @Test
    void verify_rejectsMalformedBearer() {
        assertTrue(tokens.verify(null).isEmpty());
        assertTrue(tokens.verify("").isEmpty());
        assertTrue(tokens.verify("Bearer tsy_w_xxx").isEmpty(), "presented bearer must not include 'Bearer '");
        assertTrue(tokens.verify("not-a-token").isEmpty(), "wrong prefix is silently rejected");
        assertTrue(tokens.verify("tsy_w_").isEmpty(), "too short for a real token");
    }

    @Test
    void verify_rejectsRevokedToken() {
        var fix = TenantFixture.bootstrap(tenants, "tok-revoke");
        var issued = tokens.issue(fix.project().id(), fix.user().id(), "to-revoke");

        assertTrue(tokens.verify(issued.plaintext()).isPresent(), "live token verifies before revoke");
        assertTrue(tokens.revoke(issued.token().id()));
        assertFalse(tokens.revoke(issued.token().id()), "second revoke is a no-op");
        assertTrue(tokens.verify(issued.plaintext()).isEmpty(), "revoked token must fail verification");
    }

    @Test
    void verify_rejectsPrefixCollisionWithoutBcryptMatch() {
        var fix = TenantFixture.bootstrap(tenants, "tok-collision");
        var real = tokens.issue(fix.project().id(), fix.user().id(), "real");

        // Forge a string with the same lookup prefix but different remainder.
        String prefix = real.plaintext().substring(0, ApiKeyService.PREFIX_LEN);
        String forgery = prefix + "tamperedbutwrong";

        // Sanity: prefix matches an existing row, so the lookup hits.
        assertNotEquals(real.plaintext(), forgery);
        assertTrue(repo.findByPrefix(prefix).isPresent(), "prefix should hit");

        assertTrue(tokens.verify(forgery).isEmpty(), "bcrypt must reject when only the lookup prefix matches");
    }

    @Test
    void issuedTokensAreDistinct() {
        var fix = TenantFixture.bootstrap(tenants, "tok-uniq");
        var a = tokens.issue(fix.project().id(), fix.user().id(), "a");
        var b = tokens.issue(fix.project().id(), fix.user().id(), "b");
        assertNotEquals(a.plaintext(), b.plaintext());
        assertNotEquals(a.token().tokenPrefix(), b.token().tokenPrefix());
    }
}
