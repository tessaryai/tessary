// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pins the exact invariant {@link OnboardingRepository#trafficWindow} depends on being additive
 * rather than a silent regression of: it advances the onboarding ladder's LISTENING → FITTING
 * rung on ANY span, tagged or not — it must NOT gain a call-site predicate, because that is what
 * the gate-clause instrument already measures (see that method's own javadoc). The connect gate's
 * stricter, additive question — has a TAGGED span arrived — lives entirely in
 * {@code SubstrateReadRepository#hasTaggedSpan} instead.
 */
@SpringBootTest
class OnboardingRepositoryTest {

    @Autowired
    OnboardingRepository onboarding;

    @Autowired
    SubstrateWriter writer;

    @Autowired
    TenantService tenants;

    @Test
    void trafficWindow_advancesOnAnySpan_taggedOrNot() throws InterruptedException {
        String projectId =
                TenantFixture.bootstrap(tenants, "onboarding-traffic").project().id();

        assertTrue(onboarding.trafficWindow(projectId).isEmpty(), "no traffic yet -- stays at LISTENING");

        // An UNTAGGED span (no tessary.call_site.id) -- the ladder must still advance on it. This is
        // the exact behaviour SubstrateReadRepository#hasTaggedSpan is NOT allowed to change.
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        writer.enqueue(
                projectId,
                List.of(new RawEntry(
                        "onb-trace-root",
                        null,
                        "agent",
                        "user question",
                        "agent answer",
                        null,
                        Map.of("session.id", "conv-onb-trace"),
                        null,
                        "onb-trace",
                        t0,
                        KindNormalizer.AGENT)));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        assertTrue(
                onboarding.trafficWindow(projectId).isPresent(),
                "an untagged span still advances listening -> fitting");
    }

    @Test
    void trafficWindow_isProjectScoped() {
        String pidA =
                TenantFixture.bootstrap(tenants, "onboarding-scope-a").project().id();
        String pidB =
                TenantFixture.bootstrap(tenants, "onboarding-scope-b").project().id();

        assertFalse(onboarding.trafficWindow(pidA).isPresent());
        assertFalse(onboarding.trafficWindow(pidB).isPresent());
    }
}
