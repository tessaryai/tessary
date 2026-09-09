// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.web.ApiResponse;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
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
 * The sessions read API (substrate-model.md §7.5).
 *
 * <p>Two contracts are under test, and the second is the unusual one:
 *
 * <ol>
 *   <li>A session's totals are the SUM of its traces' already-materialized rollup columns, and the
 *       response reports how many of those traces are still unsettled — because a sum over moving
 *       addends is a lower bound and has to say so.
 *   <li><b>There is no way to sort sessions by cost or tokens, and there is a test for its absence.</b>
 *       That is not an oversight waiting to be filled in: serving it would mean summing every session in
 *       the project before a page could be chosen. A future parameter would need a session
 *       materialization with its own staleness contract behind it, and this assertion is what makes
 *       adding one an explicit decision rather than a small convenience.
 * </ol>
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
                        50L,
                        null,
                        null,
                        null),
                "0.01",
                "0.02",
                null,
                null,
                "inferred");
        rollUp(first, t0);

        String second = SubstrateV2Fixtures.traceId();
        fx.trace(pid, second, sessionId, t0.plusSeconds(60));
        fx.withUsage(
                fx.span(pid, second, SubstrateV2Fixtures.spanId(), null, "llm", t0.plusSeconds(60), null),
                10L,
                5L,
                null,
                null,
                null);

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

        // Two traces at "cyrano", one at "otto" — cyrano is dominant by count.
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
                50L,
                null,
                null,
                null);
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
                75L,
                null,
                null,
                null);
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
                5L,
                null,
                null,
                null);
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

    /**
     * The §7.5 contract, asserted as an absence.
     *
     * <p>Reflection rather than prose because prose does not fail a build. If someone adds a {@code sort}
     * parameter to this endpoint, this test tells them the design decision they are overturning before
     * the query that scans every session in the project reaches production.
     */
    @Test
    @DisplayName("no surface may list sessions sorted by cost or tokens — the endpoint has no such parameter")
    void theSessionsListHasNoCostOrTokenSortParameter() {
        Method list = Arrays.stream(SessionsController.class.getDeclaredMethods())
                .filter(m -> "list".equals(m.getName()))
                .findFirst()
                .orElseThrow();
        List<String> params = Arrays.stream(list.getParameters())
                .map(p -> p.getName().toLowerCase(java.util.Locale.ROOT))
                .toList();
        assertTrue(
                params.stream().noneMatch(p -> p.contains("sort") || p.contains("cost") || p.contains("token")),
                "sessions carry no rollup; ordering them by a summed quantity needs a materialization with "
                        + "its own staleness contract, not a request parameter. Parameters were: " + params);
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
