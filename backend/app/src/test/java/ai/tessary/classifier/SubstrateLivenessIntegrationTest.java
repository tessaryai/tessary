// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance for the onboarding live-trace detector ({@link SubstrateReadRepository#hasSpans}),
 * against the real pgvector Postgres (Testcontainers). An empty project is not live; once a
 * live trace lands as substrate liveness flips true; liveness is strictly project-scoped (one project's
 * traces never leak into another's). The {@code EXISTS} short-circuit must preserve those exact
 * semantics. This is the signal the cookie-auth {@code /substrate/status} endpoint surfaces to
 * {@code Setup.tsx}.
 *
 * <p>Also covers the connect-gate reads ({@link SubstrateReadRepository#hasTaggedSpan},
 * {@link SubstrateReadRepository#spansReceived}, {@link SubstrateReadRepository#taggedSpans}) —
 * additive, so they ride the same {@code writer.enqueue}/{@code oneTrace} fixtures the untagged-count
 * assertions already use rather than a second test method duplicating the setup.
 */
@SpringBootTest
class SubstrateLivenessIntegrationTest {

    @Autowired
    SubstrateReadRepository substrate;

    @Autowired
    SubstrateWriter writer;

    @Autowired
    TenantService tenants;

    private static List<RawEntry> oneTrace(String traceId) {
        return oneTrace(traceId, null);
    }

    private static List<RawEntry> oneTrace(String traceId, @Nullable String callSiteId) {
        Map<String, Object> meta = callSiteId == null
                ? Map.of("session.id", "conv-" + traceId)
                : Map.of("session.id", "conv-" + traceId, "tessary.call_site.id", callSiteId);
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        return List.of(new RawEntry(
                traceId + "-root",
                null,
                "agent",
                "user question",
                "agent answer",
                null,
                meta,
                null,
                traceId,
                t0,
                KindNormalizer.AGENT));
    }

    @Test
    void hasSpans_isFalseUntilLiveTraceLands_andIsProjectScoped() throws InterruptedException {
        String pidA =
                TenantFixture.bootstrap(tenants, "live-detect-a").project().id();
        String pidB =
                TenantFixture.bootstrap(tenants, "live-detect-b").project().id();

        // Empty project: the onboarding poll sees no live trace yet.
        assertFalse(substrate.hasSpans(pidA));
        assertFalse(substrate.hasSpans(pidB));

        // A live OTLP trace lands as substrate (no graded run) for project A only.
        writer.enqueue(pidA, oneTrace("tr-live"));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        assertTrue(substrate.hasSpans(pidA), "live trace landed for A");
        assertFalse(substrate.hasSpans(pidB), "B is untouched — liveness is project-scoped");

        // The EXISTS short-circuit must still report true once MORE than one row is present.
        writer.enqueue(pidA, oneTrace("tr-live-2"));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");
        assertTrue(substrate.hasSpans(pidA), "still live with multiple traces");

        // Untagged count: both traces above carry no tessary.call_site.id, so they are invisible to
        // grading and the instrument nudge counts them; a tagged trace never inflates the count.
        assertEquals(2, substrate.untaggedSpans(pidA), "both untagged spans counted");
        assertEquals(0, substrate.untaggedSpans(pidB), "untagged count is project-scoped");

        // hasTaggedSpan/spansReceived/taggedSpans are false/0/0 before this point (both prior
        // traces above are untagged) and flip only once a call-site-tagged span lands.
        assertFalse(substrate.hasTaggedSpan(pidA), "no tagged span has landed yet");
        assertEquals(2, substrate.spansReceived(pidA), "two untagged spans received so far");
        assertEquals(0, substrate.taggedSpans(pidA), "none of them tagged");

        writer.enqueue(pidA, oneTrace("tr-tagged", "support.answer"));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");
        assertEquals(2, substrate.untaggedSpans(pidA), "a tagged span is not untagged");

        assertTrue(substrate.hasTaggedSpan(pidA), "the tagged span flips the connect gate's signal");
        assertFalse(substrate.hasTaggedSpan(pidB), "hasTaggedSpan is project-scoped");
        assertEquals(3, substrate.spansReceived(pidA), "all three spans counted");
        assertEquals(1, substrate.taggedSpans(pidA), "exactly the one tagged span counted");
    }
}
