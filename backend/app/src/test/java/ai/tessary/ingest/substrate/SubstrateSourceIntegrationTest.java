// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.ImportFilter;
import ai.tessary.ingest.IngestionSource;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.SourceFactory;
import ai.tessary.sources.SourceRow;
import ai.tessary.sources.SourceService;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance test for the substrate-as-source chain (substrate → {@code sdk} source → run): data ingested
 * through the OTLP receiver lands in {@code span} + {@code span_payload}, and the
 * synthetic {@code sdk} source surfaces it as gradable {@link RawEntry}s — carrying the span's
 * already-resolved {@code call_site_id} so a run grades it without per-source mappings.
 * Exercised against the real pgvector Postgres (Testcontainers).
 *
 * <p><b>Ids round-trip verbatim now.</b> A {@code RawEntry}'s {@code traceId} is the producer's trace id and
 * its {@code sourceExternalId} is the composite handle {@code "<trace_id>:<span_id>"} — the whole key,
 * because a span id alone resolves nothing. That is what {@code fetchOne} takes back, and what the export
 * mapper writes into {@code context.trace_id}/{@code context.span_id}.
 */
@SpringBootTest
class SubstrateSourceIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    SourceService sources;

    @Autowired
    SourceFactory sourceFactory;

    @Autowired
    TenantService tenants;

    @Test
    void ensureSdkSourceIsIdempotent() {
        String pid =
                TenantFixture.bootstrap(tenants, "sdk-source-idem").project().id();
        SourceRow first = sources.ensureSdkSource(pid);
        SourceRow second = sources.ensureSdkSource(pid);
        assertEquals(SourceService.SDK_PROVIDER, first.provider());
        assertEquals(first.id(), second.id(), "the sdk source is a per-project singleton");
        assertEquals(
                1,
                sources.list(pid).stream()
                        .filter(s -> SourceService.SDK_PROVIDER.equals(s.provider()))
                        .count());
    }

    private SubstrateV2Fixtures fixtures() {
        return new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    void substrateSourceSurfacesIngestedSpansWithResolvedCallSite() {
        String pid =
                TenantFixture.bootstrap(tenants, "sdk-source-fetch").project().id();
        SourceRow sdk = sources.ensureSdkSource(pid);

        // Stand up the substrate an OTLP ingest would produce — including a resolved call_site_id and a
        // code.filepath the producer stamps.
        Instant now = Instant.parse("2026-06-10T00:00:00Z");
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fixtures().session(pid, sessionId, now);
        var ref = fixtures()
                .spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("agent")
                .name("my-agent")
                .model("gpt-x")
                .callSiteId("cs-checkout-123")
                .at(now)
                .payload("what is 2+2?", "4", "{\"code.filepath\":\"app/agent.py\"}")
                .writeRef();
        String handle = ref.traceId() + ':' + ref.spanId();

        try (IngestionSource src = sourceFactory.open(sdk)) {
            assertEquals(SourceService.SDK_PROVIDER, src.provider());
            assertTrue(src.returnsFullIoOnFetch(), "substrate stores full, untruncated I/O");

            List<RawEntry> raws;
            try (var stream = src.fetch(ImportFilter.empty())) {
                raws = stream.toList();
            }
            RawEntry r = raws.stream()
                    .filter(e -> handle.equals(e.sourceExternalId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("ingested span not surfaced by the sdk source"));

            assertEquals("cs-checkout-123", r.callSiteId(), "the pre-resolved call site is threaded through");
            assertEquals("what is 2+2?", r.input());
            assertEquals("4", r.output(), "full output, never truncated");
            assertEquals(traceId, r.traceId(), "traceId is the PRODUCER's trace id, verbatim");
            assertEquals(KindNormalizer.AGENT, r.operationKind());
            Map<String, Object> meta = java.util.Objects.requireNonNull(r.metadata(), "substrate metadata");
            assertEquals("app/agent.py", meta.get("code.filepath"), "producer-stamped code.filepath is preserved");

            // Detail + trace re-fetch paths used by preview / dataset runs.
            assertEquals("4", src.fetchOne(handle, ImportFilter.empty()).output());
            assertEquals(1, src.fetchTrace(traceId, ImportFilter.empty()).size());
        }
    }

    /**
     * A bare span id is not a handle, and the source says so rather than resolving it against whichever
     * trace happens to hold a span with that id. Under v1's globally-unique surrogates a bare id was a
     * valid address; under producer keys it is a wrong answer waiting to happen.
     */
    @Test
    void fetchOne_rejectsABareSpanIdWithTheExpectedShape() {
        String pid =
                TenantFixture.bootstrap(tenants, "sdk-source-handle").project().id();
        SourceRow sdk = sources.ensureSdkSource(pid);
        try (IngestionSource src = sourceFactory.open(sdk)) {
            IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> src.fetchOne("just-a-span-id", ImportFilter.empty()));
            assertTrue(
                    java.util.Objects.requireNonNull(e.getMessage()).contains("<trace_id>:<span_id>"), e.getMessage());
        }
    }

    /**
     * The span tree survives the handle change: a child's {@code parentId} still equals its parent's
     * {@code sourceExternalId}, which is what every downstream transform groups on.
     */
    @Test
    void parentIdIsTheParentsHandle_soTheTreeHolds() {
        String pid =
                TenantFixture.bootstrap(tenants, "sdk-source-tree").project().id();
        SourceRow sdk = sources.ensureSdkSource(pid);
        Instant now = Instant.parse("2026-06-10T00:00:00Z");
        String traceId = SubstrateV2Fixtures.traceId();
        var parent = fixtures()
                .spanSeed(pid)
                .traceId(traceId)
                .name("root")
                .at(now)
                .payload("q", "a")
                .writeRef();
        var child = fixtures()
                .spanSeed(pid)
                .traceId(traceId)
                .parentSpanId(parent.spanId())
                .name("child")
                .at(now.plusSeconds(1))
                .payload("q2", "a2")
                .writeRef();

        try (IngestionSource src = sourceFactory.open(sdk)) {
            List<RawEntry> entries = src.fetchTrace(traceId, ImportFilter.empty());
            assertEquals(2, entries.size());
            RawEntry childEntry = entries.stream()
                    .filter(e -> (traceId + ':' + child.spanId()).equals(e.sourceExternalId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(traceId + ':' + parent.spanId(), childEntry.parentId());
        }
    }

    @Test
    void fetchRecentByCallSite_returnsOnlyThatCallSitesSpans_newestFirst() {
        // Tag-as-the-model: synthesis grounds by call_site_id directly. The substrate source reads
        // ix_span_call_site — only that call site's spans, newest first, limit honored.
        String pid =
                TenantFixture.bootstrap(tenants, "sdk-source-bycs").project().id();
        SourceRow sdk = sources.ensureSdkSource(pid);

        insertSpanForCallSite(pid, "cs-A", Instant.parse("2026-01-01T00:00:01Z"));
        insertSpanForCallSite(pid, "cs-A", Instant.parse("2026-01-01T00:00:03Z")); // newer
        insertSpanForCallSite(pid, "cs-B", Instant.parse("2026-01-01T00:00:02Z")); // a different call site

        try (IngestionSource src = sourceFactory.open(sdk)) {
            List<RawEntry> a = src.fetchRecentByCallSite("cs-A", 10, null);
            assertEquals(2, a.size(), "only the two cs-A spans");
            assertTrue(a.stream().allMatch(e -> "cs-A".equals(e.callSiteId())), "no other call site leaks in");
            assertEquals("2026-01-01T00:00:03Z", a.get(0).timestamp(), "newest first by started_at");
            assertEquals(1, src.fetchRecentByCallSite("cs-A", 1, null).size(), "limit is honored");
            assertTrue(src.fetchRecentByCallSite("cs-unknown", 10, null).isEmpty(), "unknown call site → empty");

            assertEquals(
                    2,
                    src.fetchRecentByCallSite("cs-A", 10, Instant.parse("2026-01-01T00:00:00Z"))
                            .size(),
                    "notBefore before both spans: both eligible");
            assertEquals(
                    1,
                    src.fetchRecentByCallSite("cs-A", 10, Instant.parse("2026-01-01T00:00:02Z"))
                            .size(),
                    "notBefore excludes the older span");
            assertTrue(
                    src.fetchRecentByCallSite("cs-A", 10, Instant.parse("2026-01-01T00:00:04Z"))
                            .isEmpty(),
                    "notBefore after both spans: none eligible");
        }
    }

    private void insertSpanForCallSite(String pid, String callSiteId, Instant startedAt) {
        fixtures()
                .spanSeed(pid)
                .traceId(SubstrateV2Fixtures.traceId())
                .callSiteId(callSiteId)
                .name("chat")
                .model("gpt-x")
                .at(startedAt)
                .payload("q", "a")
                .write();
    }

    @Test
    void factoryOpensSdkProviderAsSubstrateSource() {
        String pid =
                TenantFixture.bootstrap(tenants, "sdk-source-factory").project().id();
        SourceRow sdk = sources.ensureSdkSource(pid);
        try (IngestionSource src = sourceFactory.open(sdk)) {
            assertSame(SubstrateSource.class, src.getClass());
        }
    }
}
