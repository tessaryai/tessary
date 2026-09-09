// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web-layer acceptance for the v2 trace read API.
 *
 * <p>Driven directly against the controller with a real bootstrapped tenant, so {@code requireProject}
 * and {@code ORG_VIEW} are exercised for real rather than mocked away.
 *
 * <p>What these assert, beyond "the endpoint works":
 *
 * <ul>
 *   <li>The list serves the rollup worker's numbers. The provenance case is in
 *       {@code TraceSubstrateRepositoryTest}; here the concern is that the wire carries them intact,
 *       including the three-way distinction between unsettled, no-usage and unpriced that a single em
 *       dash used to flatten.
 *   <li>A deep link minted before the cutover still resolves. The path parameter is a producer trace id
 *       now, and every bookmark in existence carries a v1 ULID.
 *   <li>A cursor minted before the cutover degrades to page one instead of resuming from a point that is
 *       not on the v2 ordering.
 * </ul>
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false"
        })
class TracesControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    TracesController controller;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    private record Tenant(TenantContext ctx, String org, String proj, String pid) {}

    private Tenant tenant(String slug) {
        var fix = TenantFixture.bootstrap(tenants, slug);
        return new Tenant(
                new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null),
                fix.org().slug(),
                fix.project().slug(),
                fix.project().id());
    }

    @Test
    @DisplayName("a trace lists with its rollup columns and opens with its spans; an unknown id is a 404")
    void listsAndOpensTrace_andUnknownIdIs404() {
        Tenant t = tenant("traces-api");
        Instant t0 = Instant.parse("2026-06-17T12:00:00Z");
        String traceId = SubstrateV2Fixtures.traceId();
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.trace(t.pid(), traceId, sessionId, t0);

        SpanRow root = fx.span(t.pid(), traceId, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(2));
        root = fx.withUsage(root, 120L, 30L, 10L, null, 5L);
        root = fx.withCost(root, "0.0012", "0.0009", null, null, "inferred");
        root = fx.withPreviews(root, "what is X?", "X is a thing", "checkout_summarizer");
        fx.payload(root, "[{\"role\":\"user\"}]", "X is a thing", "{\"gen_ai.system\":\"anthropic\"}");
        SpanRow tool = fx.span(
                t.pid(),
                traceId,
                SubstrateV2Fixtures.spanId(),
                root.id(),
                "tool",
                t0.plusMillis(100),
                t0.plusSeconds(1));
        rollUp(t.pid(), traceId, t0);

        var page = ok(controller.list(
                t.ctx(), t.org(), t.proj(), null, null, null, null, null, null, null, null, null, null));
        assertEquals(1, page.traces().size());
        var item = page.traces().get(0);
        assertEquals(traceId, item.id(), "the producer's trace id, not a surrogate");
        assertEquals(sessionId, item.session());
        assertEquals(Integer.valueOf(2), item.spanCount());
        assertEquals(Long.valueOf(165L), item.totalTokens(), "120 + 30 + 10 + 5, from the rollup column");
        assertEquals(Long.valueOf(10L), item.cacheReadTokens(), "the typed cache bucket reaches the wire");
        assertEquals(Long.valueOf(5L), item.reasoningTokens());
        assertNotNull(item.totalCost());
        assertEquals(Integer.valueOf(0), item.unpricedSpans());
        assertTrue(item.isSettled());
        assertEquals("ok", item.status());
        assertEquals("what is X?", item.inputPreview(), "copied down from the root span by the rollup");
        assertEquals("checkout_summarizer", item.callSiteId());

        var detail = ok(controller.detail(t.ctx(), t.org(), t.proj(), traceId));
        assertEquals(traceId, detail.trace().id());
        assertEquals(
                item.totalTokens(),
                detail.trace().totalTokens(),
                "the detail header and the list row are the same projection of the same columns");
        assertEquals(2, detail.spans().size());
        var rootView = detail.spans().stream()
                .filter(s -> s.parentSpanId() == null)
                .findFirst()
                .orElseThrow();
        assertEquals("llm", rootView.kind());
        assertEquals("inferred", rootView.costSource(), "the only correct way to read a null cost");
        assertEquals(Long.valueOf(165L), rootView.totalTokens());
        assertTrue(rootView.payloadAvailable());
        assertEquals("X is a thing", rootView.output());
        assertNotNull(rootView.attributes());

        var toolView = detail.spans().stream()
                .filter(s -> tool.id().equals(s.id()))
                .findFirst()
                .orElseThrow();
        assertFalse(toolView.payloadAvailable(), "no payload row — distinct from a payload that was purged");
        assertNull(toolView.input());
        assertEquals("unpriced", toolView.costSource());

        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.detail(t.ctx(), t.org(), t.proj(), "no-such-trace"))
                        .getStatusCode());
    }

    @Test
    @DisplayName("unsettled, no-usage and unpriced reach the wire as three different answers")
    void theWireKeepsTheThreeKindsOfAbsentNumberApart() {
        Tenant t = tenant("traces-api-honesty");
        Instant t0 = Instant.parse("2026-06-18T12:00:00Z");

        String pending = SubstrateV2Fixtures.traceId();
        fx.llmSpan(t.pid(), pending, t0.plusSeconds(30));

        String quiet = SubstrateV2Fixtures.traceId();
        fx.span(t.pid(), quiet, SubstrateV2Fixtures.spanId(), null, "tool", t0.plusSeconds(20), t0.plusSeconds(21));
        rollUp(t.pid(), quiet, t0);

        String unpriced = SubstrateV2Fixtures.traceId();
        fx.withUsage(fx.llmSpan(t.pid(), unpriced, t0.plusSeconds(10)), 400L, 100L, null, null, null);
        rollUp(t.pid(), unpriced, t0);

        var byId = ok(controller.list(
                        t.ctx(), t.org(), t.proj(), null, null, null, null, null, null, null, null, null, null))
                .traces()
                .stream()
                .collect(java.util.stream.Collectors.toMap(TraceDtos.TraceListItem::id, i -> i));

        assertFalse(byId.get(pending).isSettled());
        assertNull(byId.get(pending).totalTokens(), "not yet rolled up — absent, not zero");
        assertNull(byId.get(pending).status(), "and it has no error count, so it is neither ok nor errored");

        assertTrue(byId.get(quiet).isSettled());
        assertNull(byId.get(quiet).totalTokens(), "settled and genuinely tokenless");
        assertEquals("ok", byId.get(quiet).status());

        assertTrue(byId.get(unpriced).isSettled());
        assertEquals(Long.valueOf(500L), byId.get(unpriced).totalTokens());
        assertNull(byId.get(unpriced).totalCost(), "no rate for this model — null, never $0");
        assertEquals(Integer.valueOf(1), byId.get(unpriced).unpricedSpans(), "and the row says how much");
    }

    @Test
    @DisplayName("the legacy-ULID deep-link shim is retired: only a producer trace id resolves")
    void detailResolvesOnlyAProducerTraceId() {
        Tenant t = tenant("traces-api-shim");
        Instant t0 = Instant.parse("2026-06-19T12:00:00Z");
        String traceId = SubstrateV2Fixtures.traceId();
        fx.llmSpan(t.pid(), traceId, t0);
        rollUp(t.pid(), traceId, t0);

        assertEquals(
                traceId,
                ok(controller.detail(t.ctx(), t.org(), t.proj(), traceId))
                        .trace()
                        .id(),
                "the producer id is the only path there is");

        // A bookmark minted before the cutover carried a platform ULID and was translated through
        // substrate_v2_id_map. The map was dropped with the rest of the backfill machinery in 0083, so the
        // link 404s. That is the accepted end of the transition — it is what "until teardown" meant — and
        // asserting it here keeps the retirement deliberate rather than a behaviour that quietly lapsed.
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.detail(t.ctx(), t.org(), t.proj(), Ids.ulid()))
                        .getStatusCode());
    }

    @Test
    @DisplayName("a cursor from before the cutover degrades to page one rather than stranding the reader")
    void aLegacyCursorDegradesToPageOne() {
        Tenant t = tenant("traces-api-cursor");
        Instant t0 = Instant.parse("2026-06-20T12:00:00Z");
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(t.pid(), traceId, t0);

        // The v1 cursor shape: [occurred_at, id], base64 of the PreviewCursor envelope, no version tag.
        String legacy = ai.tessary.ingest.PreviewCursor.encode(t0 + "\u001f" + Ids.ulid(), 0);
        var page = ok(controller.list(
                t.ctx(), t.org(), t.proj(), null, legacy, null, null, null, null, null, null, null, null));
        assertEquals(
                List.of(traceId),
                page.traces().stream().map(TraceDtos.TraceListItem::id).toList(),
                "an unversioned cursor is discarded — the reader lands on the newest page, never on nothing");

        String garbage = "!!!not-base64!!!";
        assertEquals(
                1,
                ok(controller.list(
                                t.ctx(), t.org(), t.proj(), null, garbage, null, null, null, null, null, null, null,
                                null))
                        .traces()
                        .size(),
                "and a garbled one does the same rather than 500");
    }

    @Test
    @DisplayName("the cursor round-trips a page boundary, including one in the sort's null tail")
    void theCursorPagesThroughTheList() {
        Tenant t = tenant("traces-api-paging");
        Instant t0 = Instant.parse("2026-06-21T12:00:00Z");
        String older = SubstrateV2Fixtures.traceId();
        String newer = SubstrateV2Fixtures.traceId();
        fx.trace(t.pid(), older, t0);
        fx.trace(t.pid(), newer, t0.plusSeconds(60));

        var first = ok(
                controller.list(t.ctx(), t.org(), t.proj(), 1, null, null, null, null, null, null, null, null, null));
        assertEquals(
                List.of(newer),
                first.traces().stream().map(TraceDtos.TraceListItem::id).toList());
        assertNotNull(first.nextCursor(), "there is another page, so there is a cursor");

        var second = ok(controller.list(
                t.ctx(), t.org(), t.proj(), 1, first.nextCursor(), null, null, null, null, null, null, null, null));
        assertEquals(
                List.of(older),
                second.traces().stream().map(TraceDtos.TraceListItem::id).toList());
        assertNull(second.nextCursor(), "and none at the end");
    }

    @Test
    @DisplayName("export preserves real image and document bytes as inline data: URIs, one JSONL span per line")
    void exportRoundTripsRealMediaBytes() throws Exception {
        // The endpoint TraceSpanMapper.toSpan/toSpanLine had zero production callers before this test.
        // Base64 media needs no MediaStore round trip (the bytes are already inline), so this
        // exercises the export wiring end to end without a separate media-store seeding step.
        Tenant t = tenant("traces-export");
        Instant t0 = Instant.parse("2026-09-02T12:00:00Z");
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(t.pid(), traceId, t0);

        String imgB64 = java.util.Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        String pdfB64 = java.util.Base64.getEncoder()
                .encodeToString("real pdf bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SpanRow span = fx.span(t.pid(), traceId, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(1));
        fx.payload(
                span,
                "[{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"see attached\"},"
                        + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\""
                        + imgB64 + "\"}},"
                        + "{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\""
                        + pdfB64 + "\"}}]}]",
                "ok",
                null);

        ResponseEntity<String> response = controller.export(t.ctx(), t.org(), t.proj(), traceId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                "application/x-ndjson", response.getHeaders().getContentType().toString());
        String body = java.util.Objects.requireNonNull(response.getBody());
        String[] lines = body.strip().split("\n");
        assertEquals(1, lines.length, "one JSONL line per span");
        // gen_ai.input.messages is a JSON-encoded STRING attribute (the plugin's messages-as-attributes
        // shape), so the message must be decoded from the span line before its fields can be read.
        JsonNode inputMessages = MAPPER.readTree(MAPPER.readTree(lines[0])
                .path("attributes")
                .path("gen_ai.input.messages")
                .asText());
        JsonNode message = inputMessages.get(0);
        assertTrue(message.path("has_media").asBoolean(), "a message carrying media is flagged has_media");
        List<String> parts = new java.util.ArrayList<>();
        message.path("parts").forEach(p -> parts.add(p.path("content").asText()));
        assertEquals(
                List.of("see attached", "data:image/png;base64," + imgB64, "data:application/pdf;base64," + pdfB64),
                parts,
                "the real image and document bytes are preserved as inline data: URIs");
    }

    @Test
    @DisplayName("export 404s on an unknown trace id, matching detail's existence check")
    void exportUnknownTraceId_is404() {
        Tenant t = tenant("traces-export-404");
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.export(t.ctx(), t.org(), t.proj(), "no-such-trace"))
                        .getStatusCode());
    }

    /** Roll one trace up synchronously — the scheduler is off in this context, so nothing races it. */
    private void rollUp(String pid, String traceId, Instant startedAt) {
        traces.applyBatchTimers(
                pid, List.of(new TraceV2Repository.TimerUpdate(traceId, startedAt.toString(), null, true)));
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
        traces.claimDue(500);
        traces.recompute(pid, traceId);
    }

    private static <T> T ok(ai.tessary.web.ApiResponse<T> response) {
        T data = response.data();
        assertNotNull(data, "the envelope carried no data");
        return data;
    }
}
