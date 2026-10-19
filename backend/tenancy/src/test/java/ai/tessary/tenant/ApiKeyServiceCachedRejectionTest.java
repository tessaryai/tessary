// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ApiKeyService} answers a token the cache already rejected from the cache. Against a real database only
 * {@code VerifiedTokenCacheTest}'s revoke race reaches this path, and only when the revoke lands first.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceCachedRejectionTest {

    @Mock
    ApiKeyRepository tokens;

    @Mock
    AuditLogRepository audit;

    /**
     * The bug: a cached rejection falls through to the row read and bcrypt, so every repeat of an invented token pays
     * the full verification the cache exists to skip.
     */
    @Test
    void aCachedRejectionAnswersWithoutReadingTheRow() {
        VerifiedTokenCache cache = new VerifiedTokenCache(new TokenCacheProperties());
        String bogus = ApiKeyService.TOKEN_PREFIX + "w_neverissuedatall";
        cache.rememberRejected(bogus);

        assertEquals(Optional.empty(), new ApiKeyService(tokens, audit, cache).verify(bogus));
        verifyNoInteractions(tokens);
    }
}
