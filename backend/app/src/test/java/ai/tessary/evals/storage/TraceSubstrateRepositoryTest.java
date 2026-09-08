// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance test for the v2 trace list surface: it is a filter, a sort and a page over columns a
 * writer already wrote, never an aggregate computed while the request is in flight. Exercised against
 * the real pgvector Postgres (Testcontainers), so the real schema is exercised.
 *
 * <p>The v1 half of this class — the session → turn → trace → observation spine round-trip and the
 * observation {@code kind} CHECK — went with the tables it exercised in 0083. The spine is two levels
 * now, {@code session → trace → span}, and {@link SubstrateV2Fixtures} seeds it.
 */
@SpringBootTest(
        properties = {"evals.ingest.substrate.resolvers-enabled=false", "evals.ingest.substrate.rollup-enabled=false"})
class TraceSubstrateRepositoryTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository v2traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void seedFixtures() {
        fx = new SubstrateV2Fixtures(sessions, v2traces, spans, payloads);
    }

    // ---- the v2 list surface: a filter, a sort and a page over columns already written ------------

    /**
     * <b>The point of the whole exercise.</b> The numbers the list serves are the numbers the rollup
     * worker wrote — not numbers computed while the request was in flight.
     *
     * <p>The first half is the ordinary claim: run the worker, and the list agrees with it. The second
     * half is the one that can actually fail. It overwrites the trace's stored rollup columns with values
     * no aggregate over its spans could produce, and asserts the list serves those instead. A list that
     * recomputed from {@code span} would quietly correct them and pass the first half forever, which is
     * exactly how the v1 query's {@code GROUP BY o2.trace_id} went unnoticed until it was the slowest
     * statement in the product.
     */
    @Test
    void listServesTheNumbersTheRollupWorkerWrote_neverNumbersComputedDuringTheRequest() {
        String pid =
                TenantFixture.bootstrap(tenants, "v2-list-provenance").project().id();
        Instant t0 = Instant.parse("2026-08-12T10:00:00Z");
        String traceId = SubstrateV2Fixtures.traceId();

        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(2));
        fx.withCost(fx.withUsage(root, 100L, 40L, null, null, null), "0.001", "0.002", null, null, "inferred");
        SpanRow child = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), root.id(), "llm", t0, t0.plusSeconds(1));
        fx.withCost(fx.withUsage(child, 10L, 5L, null, null, null), "0.0001", "0.0002", null, null, "inferred");
        rollUp(pid, traceId, t0);

        TraceV2Row stored = v2traces.findById(pid, traceId).orElseThrow();
        assertEquals(Integer.valueOf(2), stored.spanCount(), "precondition: the worker actually ran");

        var listed = only(v2traces.list(pid, TraceV2Repository.TraceQuery.none(), null, 10, null, null, null));
        assertEquals(stored.spanCount(), listed.spanCount());
        assertEquals(stored.totalTokens(), listed.totalTokens());
        assertEquals(Long.valueOf(155L), listed.totalTokens(), "110 in + 45 out, as the worker summed them");
        assertEquals(new java.math.BigDecimal(stored.totalCost()), listed.totalCost());
        assertTrue(listed.isSettled());

        // Now make the stored rollup disagree with the spans, and watch the list side with the row.
        jdbc.sql("UPDATE trace SET span_count = 99, total_tokens = 4242, total_cost = 7.5"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();

        var again = only(v2traces.list(pid, TraceV2Repository.TraceQuery.none(), null, 10, null, null, null));
        assertEquals(Integer.valueOf(99), again.spanCount(), "the list reads the column, it does not count spans");
        assertEquals(Long.valueOf(4242L), again.totalTokens(), "…and it does not sum usage either");
        assertEquals(0, new java.math.BigDecimal("7.5").compareTo(again.totalCost()), "…nor price anything");
    }

    /**
     * Unsettled, no-usage and unpriced are three different answers, and the row keeps them apart.
     *
     * <p>They rendered as one em dash before this milestone. A live turn showing "—" for cost is not the
     * same statement as a turn that genuinely spent nothing, and neither is a turn whose model we hold no
     * rate for — that third one has a real bill we cannot name, and reading it as free is the failure the
     * {@code unpriced_spans} column exists to prevent.
     */
    @Test
    void unsettledAndNoUsageAndUnpricedAreThreeDistinguishableStates() {
        String pid =
                TenantFixture.bootstrap(tenants, "v2-list-honesty").project().id();
        Instant t0 = Instant.parse("2026-08-12T09:00:00Z");

        // 1. Never rolled up: no counters at all, and is_settled false says why.
        String pending = SubstrateV2Fixtures.traceId();
        fx.llmSpan(pid, pending, t0.plusSeconds(30));

        // 2. Settled with no usage: the producer sent none, so the buckets stay null rather than zero.
        String quiet = SubstrateV2Fixtures.traceId();
        fx.span(pid, quiet, SubstrateV2Fixtures.spanId(), null, "tool", t0.plusSeconds(20), t0.plusSeconds(21));
        rollUp(pid, quiet, t0);

        // 3. Settled, real usage, no rate: tokens present, cost null, unpriced_spans says so.
        String unpriced = SubstrateV2Fixtures.traceId();
        fx.withUsage(fx.llmSpan(pid, unpriced, t0.plusSeconds(10)), 500L, 100L, null, null, null);
        rollUp(pid, unpriced, t0);

        var byId = v2traces.list(pid, TraceV2Repository.TraceQuery.none(), null, 10, null, null, null).stream()
                .collect(java.util.stream.Collectors.toMap(TraceV2Repository.Summary::id, r -> r));

        var p = byId.get(pending);
        assertFalse(p.isSettled(), "still receiving spans — its totals are provisional, not zero");
        assertNull(p.spanCount(), "and it has no totals at all yet");
        assertNull(p.totalTokens());

        var q = byId.get(quiet);
        assertTrue(q.isSettled());
        assertEquals(Integer.valueOf(1), q.spanCount(), "settled, so the count is final");
        assertNull(q.totalTokens(), "and it is genuinely null — never 0, which would read as 'free'");
        assertEquals(Integer.valueOf(0), q.unpricedSpans(), "nothing carried usage, so nothing went unpriced");

        var u = byId.get(unpriced);
        assertTrue(u.isSettled());
        assertEquals(Long.valueOf(600L), u.totalTokens(), "the tokens are known");
        assertNull(u.totalCost(), "the cost is not — and it is null, not zero");
        assertEquals(Integer.valueOf(1), u.unpricedSpans(), "and the row says how much spend it could not price");
    }

    /** Newest first on {@code started_at}, with a keyset that neither repeats nor skips a row. */
    @Test
    void listOrdersNewestFirstAndPagesOnTheKeyset() {
        String pid = TenantFixture.bootstrap(tenants, "v2-list-order").project().id();
        Instant base = Instant.parse("2026-08-12T08:00:00Z");
        String earliest = SubstrateV2Fixtures.traceId();
        String mid = SubstrateV2Fixtures.traceId();
        String latest = SubstrateV2Fixtures.traceId();
        // Inserted in an order matching neither the clock nor the ids, so a passing assertion cannot be
        // an accident of insertion order.
        fx.trace(pid, mid, base.plusSeconds(60));
        fx.trace(pid, earliest, base);
        fx.trace(pid, latest, base.plusSeconds(120));

        var noFilter = TraceV2Repository.TraceQuery.none();
        assertEquals(List.of(latest, mid, earliest), ids(v2traces.list(pid, noFilter, null, 10, null, null, null)));

        var first = v2traces.list(pid, noFilter, null, 1, null, null, null);
        assertEquals(List.of(latest), ids(first));
        var next = v2traces.list(
                pid,
                noFilter,
                null,
                10,
                null,
                first.get(0).startedAt(),
                first.get(0).id());
        assertEquals(List.of(mid, earliest), ids(next), "page two picks up below page one, no repeat, no skip");
    }

    /**
     * A cursor from the v1 list degrades to page one rather than resuming from a point that is not on the
     * v2 ordering. The repository is given a keyset key it cannot satisfy — a surrogate ULID and an
     * instant — and the controller's decode is what rejects it; here the equivalent is a key that matches
     * nothing, which must not silently return an empty page for a project that has traces.
     */
    @Test
    void aKeysetKeyFromBeforeTheCutoverDoesNotStrandTheReader() {
        String pid = TenantFixture.bootstrap(tenants, "v2-list-legacy-cursor")
                .project()
                .id();
        Instant t0 = Instant.parse("2026-08-12T07:00:00Z");
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(pid, traceId, t0);

        // The controller discards an unversioned cursor, so the repository is asked for page one.
        assertEquals(
                List.of(traceId),
                ids(v2traces.list(pid, TraceV2Repository.TraceQuery.none(), null, 10, null, null, null)));
    }

    /**
     * Model, kind and call-site filters are semi-joins: they keep a trace when ANY of its spans matches,
     * and they compute nothing about the ones that do.
     */
    @Test
    void modelKindAndCallSiteFiltersAreSemiJoinsOverSpans() {
        String pid =
                TenantFixture.bootstrap(tenants, "v2-list-filters").project().id();
        Instant t0 = Instant.parse("2026-08-12T06:00:00Z");

        String tagged = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.span(pid, tagged, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(1));
        fx.withPreviews(root, "hi", "yo", "checkout_summarizer");
        fx.span(pid, tagged, SubstrateV2Fixtures.spanId(), root.id(), "tool", t0, t0.plusSeconds(1));

        String untagged = SubstrateV2Fixtures.traceId();
        fx.span(pid, untagged, SubstrateV2Fixtures.spanId(), null, "llm", t0.plusSeconds(10), t0.plusSeconds(11));

        var byCallSite = new TraceV2Repository.TraceQuery(null, null, "checkout_summarizer", null, null, null, null);
        assertEquals(
                List.of(tagged),
                ids(v2traces.list(pid, byCallSite, null, 10, null, null, null)),
                "only the trace with a tagged span, and the whole trace at that");

        var byKind = new TraceV2Repository.TraceQuery(null, "tool", null, null, null, null, null);
        assertEquals(List.of(tagged), ids(v2traces.list(pid, byKind, null, 10, null, null, null)));

        var unknown = new TraceV2Repository.TraceQuery(null, null, "no_such_site", null, null, null, null);
        assertTrue(
                v2traces.list(pid, unknown, null, 10, null, null, null).isEmpty(), "an unknown call site matches none");

        assertEquals(
                2,
                v2traces.list(pid, TraceV2Repository.TraceQuery.none(), null, 10, null, null, null)
                        .size(),
                "an untagged trace still lists when the filter is absent");
    }

    /**
     * A cost sort puts traces with no total last and pages through that tail without losing anyone.
     *
     * <p>The v1 query collapsed the two cases with {@code COALESCE(cost, -1)}, which made a genuinely
     * unpriced trace indistinguishable from one priced at minus a dollar and put it at the wrong end of
     * the page.
     */
    @Test
    void costSortSendsTracesWithNoTotalToTheEndAndPagesThroughThem() {
        String pid = TenantFixture.bootstrap(tenants, "v2-list-sort").project().id();
        Instant t0 = Instant.parse("2026-08-12T05:00:00Z");

        String dear = SubstrateV2Fixtures.traceId();
        fx.withCost(fx.llmSpan(pid, dear, t0), "0.5", "0.5", null, null, "inferred");
        rollUp(pid, dear, t0);

        String cheap = SubstrateV2Fixtures.traceId();
        fx.withCost(fx.llmSpan(pid, cheap, t0.plusSeconds(1)), "0.001", null, null, null, "inferred");
        rollUp(pid, cheap, t0);

        // Never rolled up: no total. Not zero.
        String unknown = SubstrateV2Fixtures.traceId();
        fx.llmSpan(pid, unknown, t0.plusSeconds(2));

        var noFilter = TraceV2Repository.TraceQuery.none();
        var page = v2traces.list(pid, noFilter, TraceV2Repository.Sort.COST, 10, null, null, null);
        assertEquals(List.of(dear, cheap, unknown), ids(page), "priced descending, then the null tail");

        // Page through it one row at a time, carrying the sort value the previous row ended on — including
        // the null one, which is what makes the second branch of the keyset predicate necessary.
        var one = v2traces.list(pid, noFilter, TraceV2Repository.Sort.COST, 1, null, null, null);
        assertEquals(List.of(dear), ids(one));
        var two = v2traces.list(
                pid,
                noFilter,
                TraceV2Repository.Sort.COST,
                1,
                one.get(0).totalCost().toPlainString(),
                one.get(0).startedAt(),
                one.get(0).id());
        assertEquals(List.of(cheap), ids(two));
        var three = v2traces.list(
                pid,
                noFilter,
                TraceV2Repository.Sort.COST,
                10,
                two.get(0).totalCost().toPlainString(),
                two.get(0).startedAt(),
                two.get(0).id());
        assertEquals(List.of(unknown), ids(three), "the null tail is reachable, not stranded past the cursor");
    }

    /** A session's totals are the SUM of its traces' rollups, and it reports how many are provisional. */
    @Test
    void sessionTotalsSumTraceRollupsAndCountTheUnsettled() {
        String pid =
                TenantFixture.bootstrap(tenants, "v2-session-totals").project().id();
        Instant t0 = Instant.parse("2026-08-12T04:00:00Z");
        String sessionId = SubstrateV2Fixtures.sessionId();

        String settled = SubstrateV2Fixtures.traceId();
        fx.trace(pid, settled, sessionId, t0);
        fx.withUsage(
                fx.span(pid, settled, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(1)),
                100L,
                20L,
                null,
                null,
                null);
        rollUp(pid, settled, t0);

        String inFlight = SubstrateV2Fixtures.traceId();
        fx.trace(pid, inFlight, sessionId, t0.plusSeconds(60));
        fx.span(pid, inFlight, SubstrateV2Fixtures.spanId(), null, "llm", t0.plusSeconds(60), null);

        var totals = v2traces.sessionTotals(pid, sessionId);
        assertEquals(2, totals.traceCount());
        assertEquals(1, totals.unsettledTraces(), "one addend is still moving, and the caller is told so");
        assertEquals(Long.valueOf(120L), totals.totalTokens(), "summed from the rollup column, not from spans");
    }

    /** Roll one trace up synchronously: arm it, bring the deadline forward, run the worker once. */
    private void rollUp(String pid, String traceId, Instant startedAt) {
        v2traces.applyBatchTimers(
                pid, List.of(new TraceV2Repository.TimerUpdate(traceId, startedAt.toString(), null, true)));
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
        v2traces.claimDue(500);
        v2traces.recompute(pid, traceId);
    }

    private static List<String> ids(List<TraceV2Repository.Summary> rows) {
        return rows.stream().map(TraceV2Repository.Summary::id).toList();
    }

    private static TraceV2Repository.Summary only(List<TraceV2Repository.Summary> rows) {
        assertEquals(1, rows.size(), "exactly one trace in this project");
        return rows.get(0);
    }
}
