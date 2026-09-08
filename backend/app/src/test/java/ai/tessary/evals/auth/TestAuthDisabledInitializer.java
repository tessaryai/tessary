// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Gives every Spring test context the posture the suite has always actually had: unauthenticated.
 *
 * <p>Registered globally in {@code src/test/resources/META-INF/spring.factories} beside
 * {@code TestcontainersPostgresInitializer}, so no test needs per-class wiring.
 *
 * <p><b>Why this exists at all.</b> Before #924, 115 of this module's 118 {@code @SpringBootTest}
 * classes ran unauthenticated for free, because leaving {@code workos.*} blank was itself the
 * off switch. #924 made absent configuration fail CLOSED, so that free ride ends and the suite has
 * to ask for what it was already getting. Stating it here rather than in 115 files is the point:
 * the property is now a deliberate choice everywhere it is made, including here.
 *
 * <p><b>How a test opts back INTO enforcement (re-decided by #852/#996).</b> Before #852,
 * {@code AuthFilter} bypassed only when a provider was absent AND this flag was set, so a test
 * could get auth enforced indirectly by configuring a fake-but-realistic {@code workos.*} key —
 * "configured provider always wins over the flag." #852 added {@code PasswordAuthProvider}, the
 * open edition's unconditionally-enabled default, which made that precedence permanently
 * unreachable (there is no longer a state where "no provider is configured"), so the flag became
 * dead in the open edition and broke the local dev stack along with it. The fix decoupled the flag
 * from provider state entirely: {@code shouldNotFilter} now returns {@code authProps.isDisabled()}
 * alone, full stop.
 *
 * <p><b>The convention this sets for every test going forward: never use a fake external-provider
 * credential as an indirect toggle for auth enforcement.</b> Override {@code evals.auth.disabled}
 * back to {@code false} directly in your own {@code @DynamicPropertySource} instead — say what you
 * mean. {@code ImportControllerTest}, {@code McpControllerTest}, and {@code OpenApiSpecDriftTest}
 * were migrated off the old {@code workos.api-key}-as-toggle pattern to this one; see tessary-paid/OPEN-CORE.md's
 * 2026-09-01 divergence-log row for the decision record.
 */
public class TestAuthDisabledInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        // TestPropertyValues.applyTo(env) inserts at the FRONT of the property source list --
        // the highest precedence, deliberately, so this default reaches all 115+ tests that never
        // think about auth at all. That is also exactly why it must not apply unconditionally any
        // more (#852/#996): once a test's own @DynamicPropertySource explicitly sets
        // evals.auth.disabled=false to opt into real enforcement, an initializer running AFTER
        // dynamic-property registration and inserting at the same front position would silently
        // win over that explicit choice -- verified empirically: without this guard,
        // ImportControllerTest/McpControllerTest/OpenApiSpecDriftTest's own override was clobbered
        // by this default, in the direction that DISABLES auth (dangerous, not just wrong -- the
        // opposite failure mode would at least be loud).
        if (!applicationContext.getEnvironment().containsProperty("evals.auth.disabled")) {
            TestPropertyValues.of("evals.auth.disabled=true").applyTo(applicationContext.getEnvironment());
        }
    }
}
