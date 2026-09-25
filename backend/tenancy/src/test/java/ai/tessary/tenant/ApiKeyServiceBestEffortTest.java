// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * {@link ApiKeyService}'s bookkeeping writes ({@code last_used_at}, the audit row) are best effort: a
 * failure in one must not decide the outcome of the verification or revocation it rides on. The
 * repositories are mocks so those writes can be made to fail; the lifecycle against a real database is
 * {@code ApiKeyManagementTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceBestEffortTest {

    @Mock
    ApiKeyRepository tokens;

    @Mock
    AuditLogRepository audit;

    private ApiKeyService service() {
        return new ApiKeyService(tokens, audit, new VerifiedTokenCache(new TokenCacheProperties()));
    }

    /**
     * The bug: a failed {@code last_used_at} write, a bookkeeping column, rejects an otherwise valid key, so
     * a database hiccup on that one UPDATE locks every client out.
     */
    @Test
    void aFailedLastUsedWriteStillVerifiesTheKey() {
        ApiKeyService service = service();
        ApiKeyService.Issued issued = service.issue("proj-1", "user-1", "ci", KeyScope.WRITE);
        when(tokens.findByPrefix(issued.token().tokenPrefix())).thenReturn(Optional.of(issued.token()));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(tokens)
                .markLastUsed(eq(issued.token().id()), anyString());

        assertEquals(Optional.of(issued.token()), service.verify(issued.plaintext()));
    }

    /**
     * The bug: an audit write failure turns a revocation that already happened into an error, so the caller
     * believes the key is still live.
     */
    @Test
    void aFailedAuditWriteStillReportsTheRevocation() {
        ApiKey live = ApiKey.of(
                "key-1", "proj-1", "user-1", "ci", "tsy_w_prefix", "hash", "2026-09-01T00:00:00Z", null, null, "write");
        when(tokens.findById("key-1")).thenReturn(Optional.of(live));
        when(tokens.revoke(eq("key-1"), anyString())).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("db down")).when(audit).insert(any());

        assertTrue(service().revoke("key-1", "user-1"));
    }
}
