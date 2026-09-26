// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.storage.RetrievedDocRepository;
import ai.tessary.storage.RetrievedDocRow;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.ToolCallRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Row;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.web.ApiResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sessions read API (substrate-model.md §7.5). A session's totals sum its traces' rollups and report how many are
 * unsettled, since that sum is a lower bound. And there is no cost or token sort, with a test for its absence: it
 * would sum every session before a page could be chosen, so adding one must be an explicit decision.
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false"
        })
class SessionsControllerTest {

    @Autowired
    SessionsController controller;

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

    @Autowired
    ToolCallRepository toolCalls;

    @Autowired
    RetrievedDocRepository retrievedDocs;

    private SubstrateV2Fixtures fx;
    private TenantContext ctx;
    private String org;
    private String proj;
    private String pid;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
        var fix = TenantFixture.bootstrap(tenants, "sessions-api");
        ctx = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        org = fix.org().slug();
        proj = fix.project().slug();
        pid = fix.project().id();
    }

    @Test
    @DisplayName("a session sums its traces' rollups and reports how many are still unsettled")
    void sessionDetailSumsTraceRollupsAndReportsUnsettled() {
        Instant t0 = Instant.parse("2026-08-12T10:00:00Z");
        String sessionId = SubstrateV2Fixtures.sessionId();

        String first = SubstrateV2Fixtures.traceId();
        fx.trace(pid, first, sessionId, t0);
        fx.withCost(
                fx.withUsage(
                        fx.span(pid, first, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(1)),
                        200L,
                        50L),
                "0.01",
                "0.02",
                null,
                null,
                "inferred");
        rollUp(first, t0);

        String second = SubstrateV2Fixtures.traceId();
        fx.trace(pid, second, sessionId, t0.plusSeconds(60));
        fx.withUsage(
                fx.span(pid, second, SubstrateV2Fixtures.spanId(), null, "llm", t0.plusSeconds(60), null), 10L, 5L);

        var detail = ok(controller.detail(ctx, org, proj, sessionId));
        assertEquals(sessionId, detail.id());
        assertEquals(2, detail.traceCount());
        assertEquals(1, detail.unsettledTraces(), "one trace is still receiving spans, so the sum is a lower bound");
        assertEquals(Long.valueOf(250L), detail.totalTokens(), "only the settled trace has a rollup to contribute");
        assertNotNull(detail.totalCost());
        assertEquals(2, detail.traces().size(), "and it carries the traces the totals came from");
        assertTrue(
                detail.traces()
                                .get(0)
                                .startedAt()
                                .compareTo(detail.traces().get(1).startedAt())
                        <= 0,
                "oldest first — a session reads forward");

        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.detail(ctx, org, proj, "no-such-session"))
                        .getStatusCode());
    }

    @Test
    @DisplayName("sessions list by recency, keyset-paged")
    void listsByRecencyAndPages() {
        Instant t0 = Instant.parse("2026-08-12T09:00:00Z");
        String older = SubstrateV2Fixtures.sessionId();
        String newer = SubstrateV2Fixtures.sessionId();
        fx.session(pid, older, t0);
        fx.session(pid, newer, t0.plusSeconds(600));

        var first = ok(controller.list(ctx, org, proj, 1, null, null));
        assertEquals(
                List.of(newer),
                first.sessions().stream().map(SessionDtos.SessionListItem::id).toList(),
                "most recently active first");
        assertNotNull(first.nextCursor());
        assertNull(first.sessions().get(0).traceCount(), "totals are not computed unless include=totals is asked for");

        var second = ok(controller.list(ctx, org, proj, 1, first.nextCursor(), null));
        assertEquals(
                List.of(older),
                second.sessions().stream().map(SessionDtos.SessionListItem::id).toList());
        assertNull(second.nextCursor());
    }

    @Test
    @DisplayName("include=totals sums each session's traces and names its dominant call site")
    void listWithTotalsSumsTracesAndNamesDominantCallSite() {
        Instant t0 = Instant.parse("2026-08-12T11:00:00Z");
        String sessionId = SubstrateV2Fixtures.sessionId();

        // cyrano is dominant by count.
        String t1 = SubstrateV2Fixtures.traceId();
        fx.trace(pid, t1, sessionId, t0);
        fx.withUsage(
                fx.spanSeed(pid)
                        .traceId(t1)
                        .spanId(SubstrateV2Fixtures.spanId())
                        .kind("llm")
                        .callSiteId("cyrano")
                        .at(t0)
                        .endedAt(t0.plusSeconds(1))
                        .write(),
                100L,
                50L);
        rollUp(t1, t0);

        String t2 = SubstrateV2Fixtures.traceId();
        fx.trace(pid, t2, sessionId, t0.plusSeconds(30));
        fx.withUsage(
                fx.spanSeed(pid)
                        .traceId(t2)
                        .spanId(SubstrateV2Fixtures.spanId())
                        .kind("llm")
                        .callSiteId("cyrano")
                        .at(t0.plusSeconds(30))
                        .endedAt(t0.plusSeconds(31))
                        .write(),
                200L,
                75L);
        rollUp(t2, t0.plusSeconds(30));

        String t3 = SubstrateV2Fixtures.traceId();
        fx.trace(pid, t3, sessionId, t0.plusSeconds(60));
        fx.withUsage(
                fx.spanSeed(pid)
                        .traceId(t3)
                        .spanId(SubstrateV2Fixtures.spanId())
                        .kind("llm")
                        .callSiteId("otto")
                        .at(t0.plusSeconds(60))
                        .endedAt(t0.plusSeconds(61))
                        .write(),
                10L,
                5L);
        rollUp(t3, t0.plusSeconds(60));

        var page = ok(controller.list(ctx, org, proj, 50, null, "totals"));
        SessionDtos.SessionListItem item = page.sessions().stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .orElseThrow();

        assertEquals(3, item.traceCount());
        assertEquals(0, item.unsettledTraces());
        assertEquals(Long.valueOf(440L), item.totalTokens(), "100+50 + 200+75 + 10+5");
        assertEquals(Long.valueOf(310L), item.inputTokens(), "100 + 200 + 10");
        assertEquals(Long.valueOf(130L), item.outputTokens(), "50 + 75 + 5");
        assertEquals("cyrano", item.dominantCallSiteId(), "cyrano appears in 2 of the 3 traces");
        assertEquals(2, item.callSiteCount(), "two distinct call sites touched this session");
    }

    @Test
    @DisplayName(
            "a session's spans come back across all its traces, each carrying only its own tool calls and documents")
    void spansAssembleEverySpanOfTheSessionWithItsOwnSideTableRows() {
        Instant t0 = Instant.parse("2026-08-12T11:00:00Z");
        String sessionId = SubstrateV2Fixtures.sessionId();
        String first = SubstrateV2Fixtures.traceId();
        String second = SubstrateV2Fixtures.traceId();
        String outside = SubstrateV2Fixtures.traceId();
        fx.trace(pid, first, sessionId, t0);
        fx.trace(pid, second, sessionId, t0.plusSeconds(60));
        SpanRow llm = fx.span(pid, first, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(1));
        SpanRow tool = fx.span(pid, first, SubstrateV2Fixtures.spanId(), llm.id(), "tool", t0.plusSeconds(2), null);
        SpanRow retrieval =
                fx.span(pid, second, SubstrateV2Fixtures.spanId(), null, "retriever", t0.plusSeconds(60), null);
        SpanRow stranger = fx.span(pid, outside, SubstrateV2Fixtures.spanId(), null, "tool", t0, null);
        fx.payload(llm, "hi", "hello", null);

        String at = t0.plusSeconds(2).toString();
        toolCalls.insertAll(List.of(
                new ToolCallRow(
                        "tc-s1-" + tool.id(),
                        pid,
                        "search",
                        null,
                        null,
                        null,
                        "{\"q\": \"x\"}",
                        "raw-ignored",
                        "{\"ok\": true}",
                        "Timeout",
                        null,
                        true,
                        2,
                        40L,
                        null,
                        at,
                        false,
                        null,
                        at,
                        at,
                        first,
                        tool.id()),
                new ToolCallRow(
                        "tc-s2-" + tool.id(),
                        pid,
                        "fetch",
                        null,
                        null,
                        null,
                        null,
                        "url=a",
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        at,
                        false,
                        null,
                        t0.plusSeconds(3).toString(),
                        at,
                        first,
                        tool.id()),
                new ToolCallRow(
                        "tc-s3-" + stranger.id(),
                        pid,
                        "elsewhere",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        at,
                        false,
                        null,
                        at,
                        at,
                        outside,
                        stranger.id())));
        retrievedDocs.insertAll(List.of(new RetrievedDocRow(
                "rd-s1-" + retrieval.id(),
                pid,
                0,
                "result",
                1,
                "doc-7",
                null,
                "passage",
                0.5,
                null,
                null,
                null,
                null,
                at,
                false,
                at,
                second,
                retrieval.id())));

        var got = ok(controller.spans(ctx, org, proj, sessionId));

        assertEquals(
                List.of(llm.id(), tool.id(), retrieval.id()),
                got.spans().stream().map(TracesController.SpanView::id).toList(),
                "every span of both session traces, oldest first, and none of a trace outside the session");
        assertEquals(false, got.spansTruncated());
        var llmView = got.spans().get(0);
        assertEquals("hi", llmView.input(), "the payload is joined onto its own span");
        assertEquals(List.of(), llmView.toolCalls());
        assertEquals(
                List.of(
                        new TracesController.ToolCallView(
                                "search", "{\"q\": \"x\"}", "{\"ok\": true}", "Timeout", 2, 40L),
                        new TracesController.ToolCallView("fetch", "url=a", null, null, null, null)),
                got.spans().get(1).toolCalls(),
                "structured args win over the raw string, and the raw string stands in when there are none");
        assertEquals(List.of(), got.spans().get(1).retrievalDocuments());
        assertEquals(
                List.of(new TracesController.RetrievalDocumentView(0, "doc-7", "passage", 0.5)),
                got.spans().get(2).retrievalDocuments());

        String empty = SubstrateV2Fixtures.sessionId();
        fx.session(pid, empty, t0);
        assertEquals(
                new SessionDtos.SessionSpans(List.of(), false),
                ok(controller.spans(ctx, org, proj, empty)),
                "a session whose traces are gone is an empty read, not a missing session");
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> controller.spans(ctx, org, proj, "no-such-session"))
                        .getStatusCode());
    }

    @Test
    @DisplayName("a session over the trace and span caps returns the first slice of each and says it was cut")
    void sessionReadsCapTracesAndSpansAndSayTheyDid() {
        // Recent, so the shared context's retention sweep leaves it.
        Instant t0 =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(7200);
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.session(pid, sessionId, t0);
        int cap = SessionReadService.SESSION_TRACE_CAP;
        List<TraceV2Row> traceRows = new ArrayList<>();
        List<SpanRow> spanRows = new ArrayList<>();
        // Minted here: the fixture's counter ids repeat within a few thousand draws.
        for (int i = 0; i <= cap; i++) {
            String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
            String startedAt = t0.plusSeconds(i).toString();
            traceRows.add(TraceV2Row.of(pid, traceId, sessionId, null, null, null, null, startedAt, startedAt));
            // Five spans each for the first thousand traces hits the span cap; one more pushes them over. The trimmed
            // trace's span starts first, so an unnarrowed read would put it first.
            int spansHere = i == cap ? 1 : i == 0 ? 6 : 5;
            for (int k = 0; k < spansHere; k++) {
                String spanAt =
                        (i == cap ? t0.minusSeconds(1) : t0.plusSeconds(i).plusMillis(k)).toString();
                spanRows.add(SubstrateV2Fixtures.spanRow(
                        pid,
                        traceId,
                        String.format(java.util.Locale.ROOT, "%016x", k),
                        null,
                        "llm",
                        spanAt,
                        null,
                        spanAt));
            }
        }
        traces.getOrCreateAll(traceRows);
        spans.upsertAll(spanRows);
        String trimmedTrace = traceRows.get(cap).id();

        var detail = ok(controller.detail(ctx, org, proj, sessionId));
        assertEquals(cap, detail.traces().size());
        assertTrue(detail.tracesTruncated(), "one trace past the cap is a truncated session, not a full one");

        var got = ok(controller.spans(ctx, org, proj, sessionId));
        assertEquals(SessionReadService.SESSION_SPAN_CAP, got.spans().size());
        assertTrue(got.spansTruncated(), "one span past the cap is a truncated read, not a full one");
        assertTrue(
                got.spans().stream().noneMatch(s -> trimmedTrace.equals(s.traceId())),
                "the spans describe the same capped traces the detail read lists");
    }

    private void rollUp(String traceId, Instant startedAt) {
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

    private static <T> T ok(ApiResponse<T> response) {
        T data = response.data();
        assertNotNull(data, "the envelope carried no data");
        return data;
    }
}
