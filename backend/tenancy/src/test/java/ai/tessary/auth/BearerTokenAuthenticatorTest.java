// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import ai.tessary.tenant.ApiKey;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What an {@code Authorization} header resolves to: only a verified key whose project still exists
 * becomes a context, and that context is the project's org, the key's owner, role member, and the
 * key's own scope.
 */
@ExtendWith(MockitoExtension.class)
class BearerTokenAuthenticatorTest {

    @Mock
    ApiKeyService keys;

    @Mock
    ProjectRepository projects;

    private final ApiKey key = new ApiKey(
            "tok_1",
            "prj_1",
            "usr_1",
            "ci",
            "tsk_abc",
            "hash",
            "2026-01-01T00:00:00Z",
            null,
            null,
            "query",
            null,
            null,
            null);

    private BearerTokenAuthenticator auth() {
        return new BearerTokenAuthenticator(keys, projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Basic dXNlcjpwdw==", "bearer tsk_abc", "tsk_abc"})
    void aHeaderThatIsNotABearerTokenResolvesToNoOne(String header) {
        assertEquals(Optional.empty(), auth().authenticate(header));
    }

    @Test
    void aVerifiedTokenWhoseProjectIsGoneResolvesToNoOne() {
        when(keys.verify("tsk_abc")).thenReturn(Optional.of(key));
        when(projects.findById("prj_1")).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), auth().authenticate("Bearer tsk_abc"));
    }

    @Test
    void aVerifiedTokenActsAsAMemberOfItsProjectWithTheKeysScope() {
        // Surrounding whitespace is trimmed: a copy-pasted token with a trailing newline still works.
        when(keys.verify("tsk_abc")).thenReturn(Optional.of(key));
        when(projects.findById("prj_1"))
                .thenReturn(Optional.of(new Project(
                        "prj_1", "org_1", "web", "Web", null, "2026-01-01T00:00:00Z", null, null, true, null)));

        assertEquals(
                Optional.of(new TenantContext("usr_1", null, "org_1", "prj_1", "member", "tok_1", KeyScope.QUERY)),
                auth().authenticate("Bearer  tsk_abc \n"));
    }
}
