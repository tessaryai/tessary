// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.metrics.MetricsQueryDtos.MetricsCatalog;
import ai.tessary.metrics.MetricsQueryDtos.MetricsFilter;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryRequest;
import ai.tessary.metrics.MetricsQueryDtos.MetricsQueryResult;
import ai.tessary.metrics.MetricsQueryDtos.MetricsRow;
import ai.tessary.metrics.MetricsQueryDtos.MetricsTopN;
import ai.tessary.metrics.MetricsQueryRepository.Result;
import ai.tessary.metrics.MetricsStatement.Shape;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TenantFixture.Setup;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The metrics engine against Postgres: every expected number below is derived by hand from the rows the test seeds.
 * Pinned here are the honesty rules (container spans, unpriced and unsettled spend, the root span's own latency, the
 * rootless and unterminated counts), the fold past top N, gap fill, compare, the cases text clock, the indexes the
 * statements must use, the statement timeout, and the key scopes.
 */
@SpringBootTest
record MetricsQueryIntegrationTest(
        SubstrateV2Fixtures fx,
        TenantService tenants,
        MetricsController controller,
        MetricsQueryService service,
        MetricsQueryRepository repo,
        MetricsProperties props,
        JdbcClient jdbc,
        TransactionTemplate transactions,
        SpanRepository spans) {

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-08T00:00:00Z";
    private static final Instant RAN = Instant.parse("2026-09-03T12:00:00Z");

    /** The bug: an agent span's cumulative usage summed with its leaf, doubling the bill. */
    @Test
    @DisplayName("a container span adds nothing to spend, calls or tokens")
    void aContainerSpanAddsNothingToSpendOrTokens() {
        String pid = project("metrics-container");
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow agent = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", RAN, RAN.plusSeconds(5));
        agent = fx.withUsage(agent, 1_000_000L, 0L);
        fx.withCost(agent, "2.00", "0", null, null, "provided");
        SpanRow leaf = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), agent.id(), "llm", RAN, RAN.plusSeconds(4));
        leaf = fx.withUsage(leaf, 1_000_000L, 0L);
        fx.withCost(leaf, "2.00", "0", null, null, "inferred");
        assertTrue(fx.rollup(pid, traceId, RAN, RAN.plusSeconds(5), true));

        Map<String, Double> v = number(pid, "llm_calls", "cost_usd", "calls", "tokens");

        assertEquals(2.0, num(v.get("cost_usd")), 1e-9, "only the llm leaf is summed");
        assertEquals(1.0, v.get("calls"));
        assertEquals(1_000_000.0, v.get("tokens"));
        assertEquals(1.0, v.get("priced_traces"));
    }

    /** The bug: a span with no price read as free, so spend looks complete while it is not. */
    @Test
    @DisplayName("an unpriced span counts as unpriced and adds no dollars")
    void anUnpricedSpanCountsAsUnpricedAndAddsNoDollars() {
        String pid = project("metrics-unpriced");
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow span = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", RAN, RAN.plusSeconds(2));
        fx.withUsage(span, 500_000L, 500_000L);
        assertTrue(fx.rollup(pid, traceId, RAN, RAN.plusSeconds(2), true));

        Map<String, Double> v = number(pid, "llm_calls", "cost_usd", "tokens");

        assertEquals(0.0, v.get("cost_usd"));
        assertEquals(1.0, v.get("unpriced_calls"));
        assertEquals(0.0, v.get("priced_traces"));
        assertEquals(1_000_000.0, v.get("tokens"), "its tokens still count");
    }

    /** The bug: a partial sum of a trace still arriving reported as its spend. */
    @Test
    @DisplayName("an unsettled trace is out of spend, but in traces and in the unsettled count")
    void anUnsettledTraceIsOutOfSpendAndInTracesAndUnsettledTraces() {
        String pid = project("metrics-unsettled");
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", RAN, RAN.plusSeconds(1));
        root = fx.withUsage(root, 1_000L, 0L);
        fx.withCost(root, "2.00", "0", null, null, "inferred");

        Map<String, Double> spans = number(pid, "llm_calls", "cost_usd");
        assertEquals(0.0, spans.get("cost_usd"));
        assertEquals(1.0, spans.get("unsettled_calls"));

        Map<String, Double> traces = number(pid, "traces", "cost_usd", "traces");
        assertEquals(0.0, traces.get("cost_usd"));
        assertEquals(1.0, traces.get("traces"));
        assertEquals(1.0, traces.get("unsettled_traces"));
        assertEquals(0.0, traces.get("priced_traces"));
    }

    /** The bug: latency read as the trace's envelope, so an async child that outlives the turn inflates p95. */
    @Test
    @DisplayName("a child that ends after the root does not move p95")
    void aChildEndingAfterTheRootDoesNotMoveP95() {
        String pid = project("metrics-child");
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", RAN, RAN.plusSeconds(10));
        fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), root.id(), "tool", RAN.plusSeconds(1), RAN.plusSeconds(60));
        assertTrue(fx.rollup(pid, traceId, RAN, RAN.plusSeconds(60), true));

        Map<String, Double> v = number(pid, "traces", "latency_p95_ms");

        assertEquals(10_000.0, num(v.get("latency_p95_ms")), 1e-6, "10 s, not the 60 s envelope");
        assertEquals(1.0, v.get("latency_n"));
    }

    /** The bug: a turn that never ended dropped silently, so stuck turns read as a fast p95. */
    @Test
    @DisplayName("an unterminated root counts as unterminated and stays out of the percentile")
    void anUnterminatedRootCountsAsUnterminatedNotInThePercentile() {
        String pid = project("metrics-stuck");
        String done = SubstrateV2Fixtures.traceId();
        fx.span(pid, done, SubstrateV2Fixtures.spanId(), null, "agent", RAN, RAN.plusSeconds(2));
        String stuck = SubstrateV2Fixtures.traceId();
        fx.span(pid, stuck, SubstrateV2Fixtures.spanId(), null, "agent", RAN, null);

        Map<String, Double> v = number(pid, "traces", "latency_p95_ms");

        assertEquals(2_000.0, num(v.get("latency_p95_ms")), 1e-6);
        assertEquals(1.0, v.get("latency_n"));
        assertEquals(1.0, v.get("unterminated_traces"));
        assertEquals(0.0, v.get("rootless_traces"));
    }

    /** The bug: an inner join on the root, which drops a rootless trace from every count. */
    @Test
    @DisplayName("a trace with no root span counts as rootless and still counts as a trace")
    void aRootlessTraceCountsAsRootless() {
        String pid = project("metrics-rootless");
        fx.trace(pid, SubstrateV2Fixtures.traceId(), RAN);
        String orphan = SubstrateV2Fixtures.traceId();
        fx.span(pid, orphan, SubstrateV2Fixtures.spanId(), "0000000000000001", "llm", RAN, RAN.plusSeconds(1));

        Map<String, Double> v = number(pid, "traces", "traces", "latency_p50_ms");

        assertEquals(2.0, v.get("traces"));
        assertEquals(2.0, v.get("rootless_traces"));
        assertEquals(0.0, v.get("latency_n"));
        assertNull(v.get("latency_p50_ms"));
    }

    /** The bug: the Unattributed row offers a filter that matches nothing, because NULL never equals the sentinel. */
    @Test
    @DisplayName("the __unattributed__ filter value matches a null call site, in and not_in")
    void theUnattributedFilterMatchesNull() {
        String pid = project("metrics-unattributed");
        llmCall(pid, SubstrateV2Fixtures.traceId(), "cs-a", "gpt-4o", RAN);
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", RAN);
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", RAN);

        MetricsFilter in = new MetricsFilter("call_site", "in", List.of(MetricsSql.UNATTRIBUTED));
        MetricsFilter notIn = new MetricsFilter("call_site", "not_in", List.of(MetricsSql.UNATTRIBUTED));

        assertEquals(
                2.0,
                only(query(pid, req("llm_calls", List.of("calls"), null, List.of(in), null, null)))
                        .get("calls"));
        assertEquals(
                1.0,
                only(query(pid, req("llm_calls", List.of("calls"), null, List.of(notIn), null, null)))
                        .get("calls"));
    }

    /** The bug: keys past top N dropped instead of folded, so the table's rows no longer add up to the total. */
    @Test
    @DisplayName("twelve models with top 5 return five named keys plus Other, and twelve total keys")
    void twelveModelsWithTopFiveReturnFiveNamedKeysPlusOtherAndTwelveTotalKeys() {
        String pid = project("metrics-fold");
        for (int i = 1; i <= 12; i++) {
            String traceId = SubstrateV2Fixtures.traceId();
            for (int c = 0; c < 13 - i; c++) llmCall(pid, traceId, null, String.format(Locale.ROOT, "m%02d", i), RAN);
        }

        MetricsQueryResult r = query(pid, req("llm_calls", List.of("calls"), "model", null, null, 5));

        assertEquals(
                List.of("m01", "m02", "m03", "m04", "m05", MetricsSql.OTHER),
                r.rows().stream().map(MetricsRow::key).toList());
        assertEquals(
                List.of(12.0, 11.0, 10.0, 9.0, 8.0, 28.0),
                r.rows().stream().map(row -> row.values().get("calls")).toList(),
                "the fold holds the seven folded models: 7+6+5+4+3+2+1");
        assertEquals("Other (7 more)", r.rows().get(5).label());
        assertEquals(28, r.rows().get(5).n());
        assertEquals(new MetricsTopN(5, 12), r.topN());
        assertTrue(r.truncated());
    }

    /** The bug: an empty day drawn as zero latency, the best day of the week, or as no point at all for its count. */
    @Test
    @DisplayName("day buckets over three days zero-fill the middle day's count and null-fill its p95")
    void dayBucketsOverThreeDaysZeroFillTheMiddleCountAndNullFillItsP95() {
        String pid = project("metrics-gaps");
        Instant day1 = Instant.parse("2026-09-01T06:00:00Z");
        Instant day3 = Instant.parse("2026-09-03T06:00:00Z");
        fx.span(
                pid,
                SubstrateV2Fixtures.traceId(),
                SubstrateV2Fixtures.spanId(),
                null,
                "agent",
                day1,
                day1.plusSeconds(1));
        fx.span(
                pid,
                SubstrateV2Fixtures.traceId(),
                SubstrateV2Fixtures.spanId(),
                null,
                "agent",
                day3,
                day3.plusSeconds(3));

        MetricsQueryResult r = query(
                pid,
                new MetricsQueryRequest(
                        "traces",
                        List.of("traces", "latency_p95_ms"),
                        null,
                        null,
                        "2026-09-01T00:00:00Z",
                        "2026-09-04T00:00:00Z",
                        "day",
                        null,
                        null));

        assertEquals(3, r.window().buckets());
        assertEquals(
                List.of("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", "2026-09-03T00:00:00Z"),
                r.rows().stream().map(MetricsRow::bucket).toList());
        assertEquals(
                List.of(1.0, 0.0, 1.0),
                r.rows().stream().map(row -> row.values().get("traces")).toList());
        MetricsRow middle = r.rows().get(1);
        assertEquals(0, middle.n());
        assertTrue(middle.values().containsKey("latency_p95_ms"));
        assertNull(middle.values().get("latency_p95_ms"));
        assertEquals(1_000.0, num(r.rows().get(0).values().get("latency_p95_ms")), 1e-6);
        assertEquals(3_000.0, num(r.rows().get(2).values().get("latency_p95_ms")), 1e-6);
    }

    /** The bug: a delta computed against the wrong span of time, or against the current window twice. */
    @Test
    @DisplayName("compare returns previous rows over the window just before")
    void compareReturnsPreviousRowsOverTheWindowBefore() {
        String pid = project("metrics-compare");
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", RAN);
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", Instant.parse("2026-09-07T23:59:59Z"));
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", Instant.parse("2026-08-25T00:00:00Z"));
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", Instant.parse("2026-08-24T23:59:59Z"));

        MetricsQueryResult r = query(
                pid, req("llm_calls", List.of("calls"), null, null, null, null).compare("previous_window"));

        assertEquals(2.0, only(r).get("calls"));
        List<MetricsRow> previous = Objects.requireNonNull(r.previousRows());
        assertEquals(1, previous.size());
        assertEquals(1.0, previous.get(0).values().get("calls"), "08-25 is in, 08-24 is before the previous window");
    }

    /** The bug: cases bucketed in the session time zone, or grouped by a text column the planner cannot sort. */
    @Test
    @DisplayName("cases group by detector and bucket by the day they opened")
    void casesGroupByDetectorAndBucketByOpenedAt() {
        String pid = project("metrics-cases");
        caseRow(pid, 1, "tool_error", "2026-09-01T03:00:00.250Z");
        caseRow(pid, 2, "tool_error", "2026-09-01T23:59:59.999Z");
        caseRow(pid, 3, "metric_drift", "2026-09-02T00:00:00Z");

        MetricsQueryResult r = query(
                pid,
                new MetricsQueryRequest(
                        "cases",
                        List.of("cases"),
                        "detector",
                        null,
                        "2026-09-01T00:00:00Z",
                        "2026-09-03T00:00:00Z",
                        "day",
                        null,
                        null));

        assertEquals(
                List.of(
                        "2026-09-01T00:00:00Z tool_error 2.0",
                        "2026-09-01T00:00:00Z metric_drift 0.0",
                        "2026-09-02T00:00:00Z tool_error 0.0",
                        "2026-09-02T00:00:00Z metric_drift 1.0"),
                r.rows().stream()
                        .map(row -> row.bucket() + " " + row.key() + " "
                                + row.values().get("cases"))
                        .toList());
    }

    /**
     * The bug: a bare ISO bound sorts above "...T00:00:00.123Z", so a cases window misses its first second and takes the
     * first second after its end. Read through the real text compare on the container's collation.
     */
    @Test
    @DisplayName("a case opened in the first second of the window counts and one in the first second after it does not")
    void aCaseOpenedInTheFirstSecondOfTheWindowCountsAndOneInTheFirstSecondAfterItDoesNot() {
        String pid = project("metrics-case-edge");
        caseRow(pid, 1, "tool_error", Instant.parse(FROM).plusMillis(123).toString());
        caseRow(pid, 2, "metric_drift", Instant.parse(TO).plusMillis(123).toString());
        caseRow(pid, 3, "classifier", Instant.parse(FROM).minusMillis(1).toString());
        caseRow(pid, 4, "behavior_drift", Instant.parse(TO).minusMillis(1).toString());

        MetricsQueryResult r = query(pid, req("cases", List.of("cases"), "detector", null, null, null));

        assertEquals(
                List.of("behavior_drift", "tool_error"),
                r.rows().stream().map(MetricsRow::key).toList(),
                "the window's first and last millisecond are in; the millisecond before it and the second after it"
                        + " are out");
    }

    /** The bug: the error rate or the latency family read from the wrong column or with a fake zero sample. */
    @Test
    @DisplayName("tool calls report their error rate, retries and latency family")
    void toolCallsErrorRateAndLatencyFamily() {
        String pid = project("metrics-tools");
        long[] latencies = {100, 200, 300, 400};
        int[] retries = {1, 0, 2, 0};
        for (int i = 0; i < 4; i++) {
            SpanRef span = fx.spanSeed(pid).kind("tool").at(RAN).writeRef();
            String id = fx.toolCall(pid, span, "search", i == 0 ? "Timeout" : null, RAN);
            jdbc.sql("UPDATE tool_call SET latency_ms = :ms, retries = :r, scope = :scope, tool_type = :type"
                            + " WHERE id = :id")
                    .param("ms", latencies[i])
                    .param("r", retries[i])
                    .param("scope", "client")
                    .param("type", "function")
                    .param("id", id)
                    .update();
        }
        SpanRef noLatency = fx.spanSeed(pid).kind("tool").at(RAN).writeRef();
        fx.toolCall(pid, noLatency, "fetch", null, RAN);

        Map<String, Double> v = number(pid, "tool_calls", "error_rate", "latency_p50_ms", "latency_p95_ms", "retries");

        assertEquals(0.2, num(v.get("error_rate")), 1e-9, "one errored call of five");
        assertEquals(1.0, v.get("errored_calls"));
        assertEquals(250.0, num(v.get("latency_p50_ms")), 1e-9);
        assertEquals(385.0, num(v.get("latency_p95_ms")), 1e-9, "300 + 0.85 x 100: the call with no latency is out");
        assertEquals(4.0, v.get("latency_n"));
        assertEquals(3.0, v.get("retries"));

        MetricsQueryResult byScope = query(pid, req("tool_calls", List.of("calls"), "scope", null, null, null));
        assertEquals(
                List.of("client", MetricsSql.UNATTRIBUTED),
                byScope.rows().stream().map(MetricsRow::key).toList());
    }

    /** The bug: a call-site row shown by its opaque id when the project named it. */
    @Test
    @DisplayName("call-site labels come from the call site's use case, and fall back to the id")
    void callSiteLabelsComeFromUseCase() {
        String pid = project("metrics-labels");
        jdbc.sql("INSERT INTO call_site (project_id, id, use_case) VALUES (:pid, 'cs-chat', 'Chat support'),"
                        + " (:pid, 'cs-bare', NULL)")
                .param("pid", pid)
                .update();
        llmCall(pid, SubstrateV2Fixtures.traceId(), "cs-chat", "gpt-4o", RAN);
        llmCall(pid, SubstrateV2Fixtures.traceId(), "cs-chat", "gpt-4o", RAN);
        llmCall(pid, SubstrateV2Fixtures.traceId(), "cs-bare", "gpt-4o", RAN);
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", RAN);

        MetricsQueryResult r = query(pid, req("llm_calls", List.of("calls"), "call_site", null, null, null));

        assertEquals(
                List.of("Chat support", "Unattributed", "cs-bare"),
                r.rows().stream().map(MetricsRow::label).toList());
    }

    /** The bug: a span column read that the covering index does not hold, so a week's window reads the heap. */
    @Test
    @DisplayName("the llm_calls statement is served by ix_span_llm_usage")
    void theLlmCallsStatementIsServedByIxSpanLlmUsage() {
        String pid = project("metrics-llm-index");
        for (int i = 0; i < 20; i++) llmCall(pid, SubstrateV2Fixtures.traceId(), "cs-" + (i % 3), "gpt-4o", RAN);
        String history = SubstrateV2Fixtures.traceId();
        for (int i = 0; i < 200; i++) llmCall(pid, history, "cs-0", "gpt-4o", Instant.parse("2026-07-01T00:00:00Z"));
        String agents = SubstrateV2Fixtures.traceId();
        fx.trace(pid, agents, RAN);
        // The covering index skips these; 1,000 of them keep ix_span_project_started the dearer plan even when VACUUM
        // could not mark a page all-visible. At 100 the two costs were within 10% and the pick flipped between runs.
        spans.upsertAll(IntStream.range(0, 1_000)
                .mapToObj(i -> SubstrateV2Fixtures.spanRow(
                        pid,
                        agents,
                        SubstrateV2Fixtures.spanId(),
                        null,
                        "agent",
                        RAN.toString(),
                        RAN.plusMillis(250).toString(),
                        RAN.toString()))
                .toList());
        MetricsStatement st = statement(
                pid,
                new MetricsQueryRequest(
                        "llm_calls",
                        List.of("cost_usd", "calls"),
                        "call_site",
                        List.of(new MetricsFilter("model", "in", List.of("gpt-4o"))),
                        FROM,
                        TO,
                        "auto",
                        null,
                        null));

        String plan = explain(st);

        assertTrue(plan.contains("Index Only Scan using ix_span_llm_usage on span s "), plan);
        assertTrue(
                plan.contains("Index Only Scan using ix_span_llm_usage on span s_1 "), "the ranking CTE too:\n" + plan);
    }

    /** The bug: a root pick spelled differently from ix_span_root, so every trace reads its spans from the heap. */
    @Test
    @DisplayName("the traces latency statement is served by ix_span_root")
    void theTracesLatencyStatementIsServedByIxSpanRoot() {
        String pid = project("metrics-root-index");
        // ix_span_root holds roots only, so children are what pk_span pays for. With none, the two plans cost the
        // same and the pick flipped between runs.
        List<SpanRow> children = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String traceId = SubstrateV2Fixtures.traceId();
            SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", RAN, RAN.plusSeconds(i));
            for (int j = 0; j < 50; j++) {
                children.add(SubstrateV2Fixtures.spanRow(
                        pid,
                        traceId,
                        SubstrateV2Fixtures.spanId(),
                        root.id(),
                        "llm",
                        RAN.toString(),
                        RAN.plusMillis(250).toString(),
                        RAN.toString()));
            }
        }
        spans.upsertAll(children);
        MetricsStatement st = statement(
                pid,
                req("traces", List.of("latency_p95_ms"), null, null, null, null).build());

        String plan = explain(st);

        assertTrue(plan.contains("Index Only Scan using ix_span_root on span s"), plan);
    }

    /** The bug: the timeout set outside the read transaction, so it binds to nothing and a slow query runs forever. */
    @Test
    @DisplayName("the repository binds the configured statement timeout inside its transaction")
    void theRepositoryBindsTheConfiguredStatementTimeout() {
        MetricsStatement st = new MetricsStatement(
                Shape.TOTAL,
                "SELECT EXTRACT(EPOCH FROM current_setting('statement_timeout')::interval) * 1000 AS n",
                Map.of(),
                List.of());

        Result r = repo.run(st, null, props.statementTimeoutMs());

        assertEquals(8000, props.statementTimeoutMs());
        assertEquals(8000, r.rows().get(0).n());
        assertNull(r.previousRows());
    }

    /** The bug: a cancelled statement surfacing as an unnamed 500 instead of "narrow the window". */
    @Test
    @DisplayName("a statement past the timeout is cancelled and named TIMED_OUT")
    void aStatementPastTheTimeoutIsCancelledAndNamed() {
        MetricsStatement slow =
                new MetricsStatement(Shape.TOTAL, "SELECT count(*) AS n FROM pg_sleep(1)", Map.of(), List.of());

        DataAccessException failure = assertThrows(DataAccessException.class, () -> repo.run(slow, null, 50));

        TessaryException named = (TessaryException) MetricsFailures.translate(failure);
        assertEquals(MetricsError.TIMED_OUT, named.error());
    }

    /** The bug: a write-only ingest key that reads the project's spend, or a query key refused its own read. */
    @Test
    @DisplayName("the controller answers a session, a query key and an admin key, and refuses a write key")
    void theControllerAnswersAQueryKeyAndRefusesAWriteKey() {
        Setup setup = TenantFixture.bootstrap(tenants, "metrics-keys");
        String pid = setup.project().id();
        String org = setup.org().slug();
        String proj = setup.project().slug();
        llmCall(pid, SubstrateV2Fixtures.traceId(), null, "gpt-4o", RAN);
        MetricsQueryRequest req =
                req("llm_calls", List.of("calls"), null, null, null, null).build();

        TenantContext session = new TenantContext(setup.user().id(), null, null, null, null, null);
        assertEquals(
                1.0,
                only(Objects.requireNonNull(
                                controller.query(session, org, proj, req).data()))
                        .get("calls"));
        MetricsCatalog catalog =
                Objects.requireNonNull(controller.catalog(session, org, proj).data());
        assertEquals(4, catalog.views().size());

        for (KeyScope scope : List.of(KeyScope.QUERY, KeyScope.ADMIN)) {
            TenantContext key = key(setup, scope);
            assertEquals(
                    1.0,
                    only(Objects.requireNonNull(
                                    controller.query(key, org, proj, req).data()))
                            .get("calls"));
            assertEquals(
                    4,
                    Objects.requireNonNull(controller.catalog(key, org, proj).data())
                            .views()
                            .size());
        }

        TenantContext write = key(setup, KeyScope.WRITE);
        TessaryException refused = assertThrows(TessaryException.class, () -> controller.query(write, org, proj, req));
        assertEquals(MetricsError.WRONG_KEY_SCOPE, refused.error());
        assertEquals(HttpStatus.FORBIDDEN, refused.error().status());
        assertEquals(
                MetricsError.WRONG_KEY_SCOPE,
                assertThrows(TessaryException.class, () -> controller.catalog(write, org, proj))
                        .error());
    }

    /** The bug: a year-long scan reaching the database because the limit was checked only in the editor. */
    @Test
    @DisplayName("a window over 90 days is a 422 through the controller")
    void aWindowOverNinetyDaysIs422ThroughTheController() {
        Setup setup = TenantFixture.bootstrap(tenants, "metrics-long");
        TenantContext session = new TenantContext(setup.user().id(), null, null, null, null, null);
        MetricsQueryRequest req = new MetricsQueryRequest(
                "traces",
                List.of("traces"),
                null,
                null,
                "2026-01-01T00:00:00Z",
                "2026-04-02T00:00:00Z",
                null,
                null,
                null);

        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> controller.query(
                        session, setup.org().slug(), setup.project().slug(), req));

        assertEquals(MetricsError.WINDOW_TOO_LONG, ex.error());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.error().status());
    }

    private static double num(@Nullable Double value) {
        return Objects.requireNonNull(value, "a measure the test expects a number for");
    }

    private String project(String name) {
        return TenantFixture.bootstrap(tenants, name).project().id();
    }

    private static TenantContext key(Setup setup, KeyScope scope) {
        return new TenantContext(
                "user-1", null, setup.org().id(), setup.project().id(), "member", "tok-1", scope);
    }

    private void llmCall(String pid, String traceId, @Nullable String callSiteId, String model, Instant at) {
        fx.spanSeed(pid)
                .traceId(traceId)
                .kind("llm")
                .callSiteId(callSiteId)
                .model(model)
                .at(at)
                .usage(10L, 5L)
                .write();
    }

    private void caseRow(String pid, long seq, String detector, String openedAt) {
        jdbc.sql("INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label,"
                        + " metric, state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at)"
                        + " VALUES (:id, :pid, :seq, :detector, 'call_site', :subject, :subject, 'm', 'open', 'title',"
                        + " 'b', 1.0, :at, :at, :at, :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("seq", seq)
                .param("detector", detector)
                .param("subject", "s" + seq)
                .param("at", openedAt)
                .update();
    }

    private Map<String, Double> number(String pid, String view, String... measures) {
        return only(query(pid, req(view, List.of(measures), null, null, null, null)));
    }

    private static Map<String, Double> only(MetricsQueryResult r) {
        assertEquals(1, r.rows().size(), "a number is one row: " + r.rows());
        return r.rows().get(0).values();
    }

    private MetricsQueryResult query(String pid, Req r) {
        return query(pid, r.build());
    }

    private MetricsQueryResult query(String pid, MetricsQueryRequest r) {
        return service.query(pid, r);
    }

    private static MetricsStatement statement(String pid, MetricsQueryRequest r) {
        MetricsQuery q = MetricsQueryValidator.resolve(r);
        return MetricsQueryPlanner.plan(q, pid, q.from(), q.to());
    }

    /**
     * VACUUM sets the visibility map, so an index-only scan is costed as on a settled table. It cannot mark a page that
     * a concurrent snapshot still needs, so a test whose pick depends on it must also seed rows the competing index
     * pays for.
     */
    private String explain(MetricsStatement st) {
        jdbc.sql("VACUUM (ANALYZE) span").update();
        jdbc.sql("VACUUM (ANALYZE) trace").update();
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
            return jdbc
                    .sql("EXPLAIN (FORMAT TEXT) " + st.sql())
                    .params(st.params())
                    .query((rs, n) -> rs.getString(1))
                    .list()
                    .stream()
                    .collect(Collectors.joining("\n"));
        }));
    }

    private static Req req(
            String view,
            List<String> measures,
            @Nullable String dimension,
            @Nullable List<MetricsFilter> filters,
            @Nullable String granularity,
            @Nullable Integer topN) {
        return new Req(view, measures, dimension, filters, granularity, topN, null);
    }

    private record Req(
            String view,
            List<String> measures,
            @Nullable String dimension,
            @Nullable List<MetricsFilter> filters,
            @Nullable String granularity,
            @Nullable Integer topN,
            @Nullable String compare) {

        Req compare(String c) {
            return new Req(view, measures, dimension, filters, granularity, topN, c);
        }

        MetricsQueryRequest build() {
            return new MetricsQueryRequest(view, measures, dimension, filters, FROM, TO, granularity, topN, compare);
        }
    }
}
