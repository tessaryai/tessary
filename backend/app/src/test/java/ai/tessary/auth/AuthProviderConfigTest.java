// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link AuthProviderConfig}'s bean selection is exactly the failure class that passes every other test while
 * being wrong: the wrong {@link AuthProvider} silently wins, everything else keeps compiling, and nothing else
 * notices.
 */
@SpringBootTest
record AuthProviderConfigTest(AuthProvider provider) {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("workos.api-key", () -> "sk_test_fake");
        r.add("workos.client-id", () -> "client_fake");
    }

    @Test
    void workosWinsWhenConfigured() {
        assertInstanceOf(
                WorkOsClient.class,
                provider,
                "a configured WorkOS BYO credential must win the AuthProvider bean selection, "
                        + "exactly matching WorkOsProperties.isEnabled()'s own blank/unset semantics");
    }
}
