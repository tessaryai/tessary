// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static ai.tessary.metrics.MetricsSql.collate;
import static ai.tessary.metrics.MetricsSql.unattributed;

import ai.tessary.metrics.MetricsMeasure.GapFill;
import ai.tessary.metrics.MetricsMeasure.Unit;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.List;

/**
 * The fixed catalog a metrics query names: each view is one base table, its gate, its event clock, and the dimensions
 * and measures over it. Every SQL string here is a constant, so a request only ever picks among them.
 */
public enum MetricsView {
    TRACES(
            "traces",
            "Traces",
            "One row per trace, under the trace's own call site. Latency is the root span's own interval. Spend and"
                    + " per-trace spend count settled traces only.",
            "trace t",
            "t.project_id = :pid AND t.is_deleted IS NOT TRUE",
            "t.started_at",
            false,
            List.of(),
            List.of(
                    dimension("call_site", "Call site", unattributed("t.call_site_id"), false),
                    dimension("name", "Trace name", unattributed("t.name"), true),
                    dimension("user", "User", unattributed("t.user_id"), true),
                    dimension("session", "Session", unattributed("t.session_id"), true),
                    dimension(
                            "outcome",
                            "Outcome",
                            collate("CASE WHEN COALESCE(t.error_count, 0) > 0 THEN 'error' ELSE 'ok' END"),
                            false)),
            List.of(
                    measure("traces", "Traces", "count(*)", Unit.COUNT, GapFill.ZERO, false, false),
                    measure(
                            "errored_traces",
                            "Errored traces",
                            "count(*) FILTER (WHERE COALESCE(t.error_count, 0) > 0)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false),
                    measure(
                            "cost_usd",
                            "Spend",
                            "sum(t.total_cost) FILTER (WHERE t.is_settled)",
                            Unit.USD,
                            GapFill.ZERO,
                            false,
                            false,
                            "unpriced_traces",
                            "unsettled_traces",
                            "priced_traces"),
                    measure(
                            "usd_per_trace",
                            "Spend per priced trace",
                            "sum(t.total_cost) FILTER (WHERE t.is_settled) / NULLIF(count(*) FILTER (WHERE t.is_settled"
                                    + " AND t.total_cost IS NOT NULL), 0)",
                            Unit.USD,
                            GapFill.NONE,
                            false,
                            false,
                            "unpriced_traces",
                            "unsettled_traces",
                            "priced_traces"),
                    measure(
                            "unpriced_traces",
                            "Unpriced traces",
                            "count(*) FILTER (WHERE t.is_settled AND COALESCE(t.unpriced_spans, 0) > 0)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false),
                    measure(
                            "unsettled_traces",
                            "Traces still settling",
                            "count(*) FILTER (WHERE NOT t.is_settled)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false),
                    measure(
                            "priced_traces",
                            "Priced traces",
                            "count(*) FILTER (WHERE t.is_settled AND t.total_cost IS NOT NULL)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false),
                    latency("latency_p50_ms", "Median latency", Latency.TURN, 0),
                    latency("latency_p95_ms", "p95 latency", Latency.TURN, 1),
                    latency("latency_p99_ms", "p99 latency", Latency.TURN, 2),
                    measure(
                            "latency_n",
                            "Traces with a finished root",
                            "count(*) FILTER (WHERE " + MetricsSql.TURN_ENDED + ")",
                            Unit.COUNT,
                            GapFill.ZERO,
                            true,
                            false),
                    measure(
                            "unterminated_traces",
                            "Traces that did not finish",
                            "count(*) FILTER (WHERE root.started_at IS NOT NULL AND root.ended_at IS NULL)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            true,
                            false),
                    measure(
                            "rootless_traces",
                            "Traces with no root span",
                            "count(*) FILTER (WHERE root.started_at IS NULL)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            true,
                            false))),

    /*
     * Every column this view reads is a key, INCLUDE or predicate column of ix_span_llm_usage (0042), so the span side
     * stays an index-only scan. Do not add a span column without widening the index.
     */
    LLM_CALLS(
            "llm_calls",
            "LLM calls",
            "One row per LLM span, under the span's own call site. Container spans are excluded, so a subtree total is"
                    + " never summed twice. A call in a trace still settling counts in no figure but Calls in traces still"
                    + " settling.",
            "span s",
            "s.project_id = :pid AND s.kind = 'llm' AND NOT s.is_deleted",
            "s.started_at",
            false,
            List.of("s.trace_id", "s.total_tokens", "s.total_cost", "s.cost_source"),
            List.of(
                    dimension("call_site", "Call site", unattributed("s.call_site_id"), false),
                    dimension("model", "Model", unattributed("s.provided_model_name"), false),
                    dimension("cost_source", "Cost source", collate("s.cost_source"), false)),
            List.of(
                    measure(
                            "calls",
                            "LLM calls",
                            "count(*) FILTER (WHERE t.is_settled AND s.total_tokens IS NOT NULL)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            true),
                    measure(
                            "tokens",
                            "Tokens",
                            "sum(s.total_tokens) FILTER (WHERE t.is_settled)",
                            Unit.TOKENS,
                            GapFill.ZERO,
                            false,
                            true,
                            "unsettled_calls"),
                    measure(
                            "cost_usd",
                            "Spend",
                            "sum(s.total_cost) FILTER (WHERE t.is_settled)",
                            Unit.USD,
                            GapFill.ZERO,
                            false,
                            true,
                            "unpriced_calls",
                            "unsettled_calls",
                            "priced_traces"),
                    measure(
                            "usd_per_trace",
                            "Spend per priced trace",
                            "sum(s.total_cost) FILTER (WHERE t.is_settled) / NULLIF(count(DISTINCT s.trace_id COLLATE"
                                    + " \"C\") FILTER (WHERE t.is_settled AND s.total_cost IS NOT NULL), 0)",
                            Unit.USD,
                            GapFill.NONE,
                            false,
                            true,
                            "unpriced_calls",
                            "unsettled_calls",
                            "priced_traces"),
                    measure(
                            "unpriced_calls",
                            "Unpriced calls",
                            "count(*) FILTER (WHERE t.is_settled AND s.cost_source = 'unpriced' AND s.total_tokens IS"
                                    + " NOT NULL)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            true),
                    measure(
                            "unsettled_calls",
                            "Calls in traces still settling",
                            "count(*) FILTER (WHERE NOT t.is_settled)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            true),
                    measure(
                            "priced_traces",
                            "Priced traces",
                            "count(DISTINCT s.trace_id COLLATE \"C\") FILTER (WHERE t.is_settled AND s.total_cost IS NOT"
                                    + " NULL)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            true),
                    measure(
                            "spending_traces",
                            "Traces with token usage",
                            "count(DISTINCT s.trace_id COLLATE \"C\") FILTER (WHERE t.is_settled AND s.total_tokens IS"
                                    + " NOT NULL)",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            true))),

    TOOL_CALLS(
            "tool_calls",
            "Tool calls",
            "One row per tool call. A call with no start time falls outside every window.",
            "tool_call tc",
            "tc.project_id = :pid AND tc.is_deleted IS NOT TRUE",
            "tc.started_at",
            false,
            List.of(),
            List.of(
                    dimension("tool", "Tool", collate(MetricsSql.TOOL_CALL_NAME), false),
                    dimension("error_type", "Error type", collate("COALESCE(tc.error_type, 'none')"), false),
                    dimension("scope", "Scope", unattributed("tc.scope"), false),
                    dimension("tool_type", "Tool type", unattributed("tc.tool_type"), false)),
            List.of(
                    measure("calls", "Tool calls", "count(*)", Unit.COUNT, GapFill.ZERO, false, false),
                    measure(
                            "retries",
                            "Retries",
                            "sum(COALESCE(tc.retries, 0))",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false),
                    latency("latency_p50_ms", "Median latency", Latency.TOOL, 0),
                    latency("latency_p95_ms", "p95 latency", Latency.TOOL, 1),
                    latency("latency_p99_ms", "p99 latency", Latency.TOOL, 2),
                    measure(
                            "latency_n",
                            "Calls with a latency",
                            "count(*) FILTER (WHERE " + Latency.TOOL.present + ")",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false))),

    CASES(
            "cases",
            "Cases",
            "One row per case, placed by the time it opened.",
            "eval_case c",
            "c.project_id = :pid",
            "c.opened_at",
            true,
            List.of(),
            List.of(
                    dimension("detector", "Detector", collate("c.detector"), false),
                    dimension("call_site", "Call site", unattributed("c.call_site_id"), false),
                    dimension("state", "State", collate("c.state"), false),
                    dimension("resolution", "Resolution", collate("COALESCE(c.resolution, 'unresolved')"), false),
                    dimension("disposition", "Disposition", collate("COALESCE(c.disposition, 'none')"), false)),
            List.of(
                    measure("cases", "Cases opened", "count(*)", Unit.COUNT, GapFill.ZERO, false, false),
                    measure(
                            "open_cases",
                            "Still open",
                            "count(*) FILTER (WHERE c.state <> 'resolved')",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false),
                    measure(
                            "resolved_cases",
                            "Resolved",
                            "count(*) FILTER (WHERE c.state = 'resolved')",
                            Unit.COUNT,
                            GapFill.ZERO,
                            false,
                            false)));

    private final String wire;
    private final String label;
    private final String description;
    private final String base;
    private final String gate;
    private final String clock;
    private final boolean textClock;
    private final List<String> carried;
    private final List<MetricsDimension> dimensions;
    private final List<MetricsMeasure> measures;

    MetricsView(
            String wire,
            String label,
            String description,
            String base,
            String gate,
            String clock,
            boolean textClock,
            List<String> carried,
            List<MetricsDimension> dimensions,
            List<MetricsMeasure> measures) {
        this.wire = wire;
        this.label = label;
        this.description = description;
        this.base = base;
        this.gate = gate;
        this.clock = clock;
        this.textClock = textClock;
        this.carried = carried;
        this.dimensions = dimensions;
        this.measures = measures;
    }

    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** The FROM clause. */
    public String base() {
        return base;
    }

    /** The WHERE fragment that scopes the base to one project; binds {@code :pid}. */
    public String gate() {
        return gate;
    }

    /** The event-time column a window and a bucket read. */
    public String clock() {
        return clock;
    }

    /** True for cases only: its clock is ISO-8601 text, so its bounds bind as {@link MetricsSql#TEXT_CLOCK} text. */
    public boolean textClock() {
        return textClock;
    }

    /**
     * The span columns a trace-joined measure may read, so a dimensioned query can carry them through its base CTE.
     * Empty on a view with no trace-joined measure.
     */
    public List<String> carried() {
        return carried;
    }

    public List<MetricsDimension> dimensions() {
        return dimensions;
    }

    public List<MetricsMeasure> measures() {
        return measures;
    }

    public static MetricsView fromWire(String wire) {
        for (MetricsView view : values()) {
            if (view.wire.equals(wire)) return view;
        }
        throw new TessaryException(MetricsError.UNKNOWN_VIEW, wire);
    }

    public MetricsMeasure measure(String wire) {
        for (MetricsMeasure m : measures) {
            if (m.wire().equals(wire)) return m;
        }
        throw new TessaryException(MetricsError.UNKNOWN_MEASURE, this.wire, wire);
    }

    public MetricsDimension dimension(String wire) {
        for (MetricsDimension d : dimensions) {
            if (d.wire().equals(wire)) return d;
        }
        throw new TessaryException(MetricsError.UNKNOWN_DIMENSION, this.wire, wire);
    }

    public MetricsDimension filterField(String wire) {
        for (MetricsDimension d : dimensions) {
            if (d.wire().equals(wire)) return d;
        }
        throw new TessaryException(MetricsError.UNKNOWN_FILTER_FIELD, this.wire, wire);
    }

    private static MetricsDimension dimension(String wire, String label, String sql, boolean highCardinality) {
        return new MetricsDimension(wire, label, sql, highCardinality);
    }

    private static MetricsMeasure measure(
            String wire,
            String label,
            String sql,
            Unit unit,
            GapFill gapFill,
            boolean needsRoot,
            boolean needsTrace,
            String... companions) {
        return new MetricsMeasure(
                wire, label, wire, sql, null, unit, gapFill, needsRoot, needsTrace, List.of(companions));
    }

    private static MetricsMeasure latency(String wire, String label, Latency family, int index) {
        return new MetricsMeasure(
                wire,
                label,
                "latency_q",
                MetricsSql.quantiles(family.value, family.present),
                index,
                Unit.MS,
                GapFill.NONE,
                family.needsRoot,
                false,
                family.companions);
    }

    /** One latency family: the value it sorts, which rows carry one, and what qualifies its percentiles. */
    private enum Latency {
        TURN(
                MetricsSql.TURN_MS,
                MetricsSql.TURN_ENDED,
                true,
                List.of("latency_n", "unterminated_traces", "rootless_traces")),
        TOOL("tc.latency_ms::float8", "tc.latency_ms IS NOT NULL AND tc.latency_ms >= 0", false, List.of("latency_n"));

        private final String value;
        private final String present;
        private final boolean needsRoot;
        private final List<String> companions;

        Latency(String value, String present, boolean needsRoot, List<String> companions) {
            this.value = value;
            this.present = present;
            this.needsRoot = needsRoot;
            this.companions = companions;
        }
    }
}
