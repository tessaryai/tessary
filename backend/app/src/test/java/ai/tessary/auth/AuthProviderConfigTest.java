// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link AuthProviderConfig}'s bean selection is exactly the failure class that passes
 * every other test while being wrong: the wrong {@link AuthProvider} silently wins, everything
 * else keeps compiling, and nothing else notices. Two separate {@code @SpringBootTest} classes,
 * one per posture, since {@code @DynamicPropertySource} is fixed per test class and Spring caches
 * contexts by property set anyway -- there's no cheaper way to boot both postures.
 *
 * <p>On the container-free {@code OpenApiSpecDriftTest} precedent: neither posture here touches
 * anything WorkOS-network-shaped or needs request-level MockMvc, so both boot on plain
 * {@code @SpringBootTest} + {@code @DynamicPropertySource}.
 */
class AuthProviderConfigTest {

    @Nested
    @SpringBootTest
    class NoWorkosConfigured {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            r.add("workos.api-key", () -> "");
            r.add("workos.client-id", () -> "");
        }

        @Autowired
        AuthProvider provider;

        @Test
        void passwordProviderIsTheOpenEditionDefault() {
            assertInstanceOf(
                    PasswordAuthProvider.class,
                    provider,
                    "with no WORKOS_API_KEY/WORKOS_CLIENT_ID, PasswordAuthProvider must be the "
                            + "single AuthProvider bean -- the open edition's zero-config default");
        }
    }

    @Nested
    @SpringBootTest
    class WorkosConfigured {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            r.add("workos.api-key", () -> "sk_test_fake");
            r.add("workos.client-id", () -> "client_fake");
        }

        @Autowired
        AuthProvider provider;

        @Test
        void workosWinsWhenConfigured() {
            assertInstanceOf(
                    WorkOsClient.class,
                    provider,
                    "a configured WorkOS BYO credential must win the AuthProvider bean selection, "
                            + "exactly matching WorkOsProperties.isEnabled()'s own blank/unset semantics");
        }
    }
}
