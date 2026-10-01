// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The app's normal, auth-enforced posture rather than the suite's unauthenticated default (see {@code
 * TestAuthDisabledInitializer}), as one shared Spring context for every class that needs it. Static properties, so the
 * classes share one cache key; a per-class {@code @DynamicPropertySource} would fork a context each.
 *
 * <p>Carries a platform staff list naming only {@code staff-984@example.com}, which {@code IngestHealthGroupTest}
 * signs up as; no other class may use that address.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@TestPropertySource(
        properties = {
            "tessary.auth.cookie-password=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "workos.api-key=",
            "workos.client-id=",
            "tessary.auth.disabled=false",
            "tessary.platform.staff-emails=staff-984@example.com"
        })
public @interface AuthEnforcedContext {}
