// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A rotate that loses a race with a concurrent revoke. The controller checks the key is live, then the
 * service re-reads it; a revoke landing between the two is only reachable by staging it, so the
 * collaborators are mocks. The rest of this controller runs against the database in
 * {@code ApiKeyManagementTest}.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyControllerRaceTest {

    @Mock
    ApiKeyService keys;

    @Mock
    ApiKeyRepository keyRepo;

    @Mock
    TenantPathResolver resolver;

    /**
     * The bug: the service's empty answer reaches the caller as a 500 from a bare {@code orElseThrow}
     * instead of 409, the same answer a rotate of an already-revoked key gets.
     */
    @Test
    void aKeyRevokedMidRotateAnswersConflict() {
        TenantContext owner = new TenantContext("u-owner", null, null, null, null, null);
        Organization org = new Organization("org-1", null, "acme", "Acme", "2026-09-01T00:00:00Z", null, null);
        Project project =
                new Project("p-1", "org-1", "app", "App", null, "2026-09-01T00:00:00Z", null, null, false, null);
        when(resolver.requireProject(owner, "acme", "app"))
                .thenReturn(new TenantPathResolver.Resolved(org, project, "owner"));
        when(keyRepo.findById("key-1"))
                .thenReturn(Optional.of(ApiKey.of(
                        "key-1",
                        "p-1",
                        "u-owner",
                        "ci",
                        "tsy_w_prefix",
                        "hash",
                        "2026-09-01T00:00:00Z",
                        null,
                        null,
                        "write")));
        when(keys.rotate("key-1", "u-owner")).thenReturn(Optional.empty());

        ApiKeyController controller = new ApiKeyController(keys, keyRepo, null, resolver);
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.rotate(owner, "acme", "app", "key-1"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
