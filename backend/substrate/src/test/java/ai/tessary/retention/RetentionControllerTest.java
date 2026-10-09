// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.config.RetentionProperties;
import ai.tessary.open.errors.RetentionError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.ops.RetentionPolicyRepository;
import ai.tessary.retention.RetentionController.RetentionUpdateRequest;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Saving retention while another build fixes it: every update is refused with 409 and nothing is written or
 * cleared, so the page cannot record a promise the sweep will not keep. The read reports the fixed value.
 */
@ExtendWith(MockitoExtension.class)
class RetentionControllerTest {

    private static final TenantContext OWNER = new TenantContext("u-owner", null, null, null, null, null);

    @Mock
    RetentionPolicyRepository policies;

    @Mock
    TenantPathResolver tenants;

    private RetentionController controller;

    @BeforeEach
    void setUp() {
        Organization org = new Organization("org-1", null, "acme", "Acme", "2026-09-01T00:00:00Z", null, null);
        Project project =
                new Project("p-1", "org-1", "app", "App", null, "2026-09-01T00:00:00Z", null, null, false, null);
        when(tenants.requireProject(OWNER, "acme", "app"))
                .thenReturn(new TenantPathResolver.Resolved(org, project, "owner"));
        RetentionResolver resolver =
                new RetentionResolver(policies, new RetentionProperties(), (projectId, dataClass) -> 21);
        controller = new RetentionController(resolver, policies, tenants);
    }

    static Stream<RetentionUpdateRequest> updates() {
        return Stream.of(
                new RetentionUpdateRequest(7, null),
                new RetentionUpdateRequest(21, 21),
                new RetentionUpdateRequest(0, null),
                new RetentionUpdateRequest(null, null));
    }

    @ParameterizedTest
    @MethodSource("updates")
    void everyUpdateIsRefusedAndNothingIsWrittenOrCleared(RetentionUpdateRequest request) {
        TessaryException ex =
                assertThrows(TessaryException.class, () -> controller.update(OWNER, "acme", "app", request));

        assertEquals(RetentionError.FIXED, ex.error());
        assertEquals(HttpStatus.CONFLICT, ex.error().status());
        assertEquals("Retention for this project is fixed at 21 days and cannot be changed", ex.getMessage());
        verify(policies, never()).upsert(any());
        verify(policies, never()).delete(any(), any());
    }

    @Test
    void theReadReportsTheFixedRetentionForEveryClass() {
        RetentionController.RetentionView view =
                Objects.requireNonNull(controller.get(OWNER, "acme", "app").data());

        assertEquals(2, view.classes().size());
        for (RetentionController.RetentionClassView c : view.classes()) {
            assertEquals(21, c.ttlDays());
            assertEquals(21, c.fixedTtlDays());
            assertFalse(c.fromPolicy());
        }
    }
}
