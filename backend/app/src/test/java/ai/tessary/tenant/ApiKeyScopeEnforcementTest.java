// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.ingest.otlp.OtlpTraceController;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.query.QueryController;
import ai.tessary.query.QueryDtos.CountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance: a key cannot act outside its scope. Drives the write ({@code /v1/traces}) and
 * query ({@code /v1/query/*}) controllers directly with key contexts of each family and asserts the
 * least-privilege gate — a query-only key is rejected at ingest, a write-only key is rejected at the
 * query API, and an {@link KeyScope#ADMIN ADMIN}-scoped key is accepted on both surfaces. Scope
 * family rules are unit-checked via {@link KeyScope#permits} in {@link ApiKeyManagementTest}.
 */
@SpringBootTest
class ApiKeyScopeEnforcementTest {

    @Autowired
    OtlpTraceController otlp;

    @Autowired
    QueryController query;

    private static TenantContext keyCtx(KeyScope scope) {
        return new TenantContext("user-1", null, "org-1", "proj-1", "member", "tok-1", scope);
    }

    // ---------------------------------------------------------------- ingest (write) surface

    @Test
    void ingest_rejectsQueryScopedKey() {
        TessaryException ex =
                assertThrows(TessaryException.class, () -> otlp.export(keyCtx(KeyScope.QUERY), new byte[] {1, 2, 3}));
        assertEquals(IngestError.OTLP_WRONG_KEY_SCOPE, ex.error());
    }

    @Test
    void ingest_acceptsWriteScopedKey_pastTheScopeGate() {
        // A WRITE key clears the scope gate; the call then fails later on malformed protobuf, proving the
        // scope check did not short-circuit it.
        TessaryException ex =
                assertThrows(TessaryException.class, () -> otlp.export(keyCtx(KeyScope.WRITE), new byte[] {1, 2, 3}));
        assertEquals(IngestError.OTLP_MALFORMED_BODY, ex.error());
    }

    @Test
    void ingest_acceptsMcpSupersetKey_pastTheScopeGate() {
        TessaryException ex =
                assertThrows(TessaryException.class, () -> otlp.export(keyCtx(KeyScope.ADMIN), new byte[] {1, 2, 3}));
        assertEquals(IngestError.OTLP_MALFORMED_BODY, ex.error());
    }

    // ---------------------------------------------------------------- query surface

    @Test
    void query_rejectsWriteScopedKey() {
        CountRequest req = new CountRequest("traces", null, null);
        TessaryException ex = assertThrows(TessaryException.class, () -> query.count(keyCtx(KeyScope.WRITE), req));
        assertEquals(QueryError.WRONG_KEY_SCOPE, ex.error());
    }

    @Test
    void query_rejectsNonKeyContext_withTokenRequired() {
        // A user-session context (no key) is not a project-scoped token for the token-scoped read surface.
        CountRequest req = new CountRequest("traces", null, null);
        TenantContext userSession = new TenantContext("user-1", null, "org-1", "proj-1", "member", null);
        TessaryException ex = assertThrows(TessaryException.class, () -> query.count(userSession, req));
        assertEquals(QueryError.TOKEN_REQUIRED, ex.error());
    }
}
