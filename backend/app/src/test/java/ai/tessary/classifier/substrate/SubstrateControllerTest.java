// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.classifier.substrate.SubstrateController.SubstrateStatusView;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The connect gate's status read against the real schema: nothing past the liveness check before traffic, and
 * once a span lands, the untagged wait state's stats, including when the newest span arrived and which service
 * sent it, so the page can tell the user their exporter is reaching us but tagging nothing.
 */
@SpringBootTest
class SubstrateControllerTest {

    @Autowired
    SubstrateController controller;

    @Autowired
    SubstrateWriter writer;

    @Autowired
    TenantService tenants;

    @Test
    void statusIsEmptyBeforeTrafficAndNamesTheNewestSpanAndItsServiceAfter() throws InterruptedException {
        TenantFixture.Setup fix = TenantFixture.bootstrap(tenants, "substrate-status");
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);

        assertEquals(new SubstrateStatusView(false, 0, false, 0, 0, null, null), status(fix, owner));

        writer.enqueue(fix.project().id(), List.of(span("sub-status-1", "2026-01-01T00:00:00Z")));
        writer.enqueue(fix.project().id(), List.of(span("sub-status-2", "2026-01-02T08:30:00Z")));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        assertEquals(
                new SubstrateStatusView(true, 2, false, 2, 0, "2026-01-02T08:30:00Z", "checkout-agent"),
                status(fix, owner));
    }

    private SubstrateStatusView status(TenantFixture.Setup fix, TenantContext owner) {
        return Objects.requireNonNull(
                controller.status(owner, fix.org().slug(), fix.project().slug()).data());
    }

    private static RawEntry span(String traceId, String at) {
        return new RawEntry(
                traceId + "-root",
                "agent",
                "user question",
                "agent answer",
                null,
                Map.of("session.id", "conv-" + traceId, "service.name", "checkout-agent"),
                null,
                traceId,
                at,
                KindNormalizer.AGENT);
    }
}
