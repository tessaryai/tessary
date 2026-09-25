// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import ai.tessary.ops.RetentionPolicyRow;
import ai.tessary.retention.RetentionController.RetentionUpdateRequest;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Saving a retention override against a ceiling another build supplies: anything longer than the
 * ceiling, "keep forever" included, is refused with 422 and nothing is written, so the page cannot
 * record a promise the sweep will not keep.
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
                new RetentionResolver(policies, new RetentionProperties(), (projectId, dataClass) -> 30);
        controller = new RetentionController(resolver, policies, tenants);
    }

    @ParameterizedTest(name = "{0} days is refused")
    @CsvSource({
        "0, Retention of forever is above the 30-day ceiling for this project",
        "31, Retention of 31 days is above the 30-day ceiling for this project",
    })
    void anOverrideAboveTheCeilingIsRefusedAndNothingIsWritten(int ttlDays, String message) {
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> controller.update(OWNER, "acme", "app", new RetentionUpdateRequest(ttlDays, null)));

        assertEquals(RetentionError.ABOVE_CEILING, ex.error());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.error().status());
        assertEquals(message, ex.getMessage());
        verify(policies, never()).upsert(any());
    }

    @Test
    void anOverrideAtTheCeilingIsSaved() {
        controller.update(OWNER, "acme", "app", new RetentionUpdateRequest(30, null));

        ArgumentCaptor<RetentionPolicyRow> saved = ArgumentCaptor.forClass(RetentionPolicyRow.class);
        verify(policies).upsert(saved.capture());
        assertEquals(30, saved.getValue().ttlDays());
    }
}
