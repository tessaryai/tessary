// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class MetricsViewTest {

    private static final Pattern SNAKE_CASE = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");

    /** The bug: the editor offers a view, dimension or measure that the engine then refuses with a 400. */
    @Test
    @DisplayName("every view resolves each dimension and measure it declares, and declares the contract's catalog")
    void everyViewResolvesEveryDeclaredDimensionAndMeasure() {
        for (MetricsView view : MetricsView.values()) {
            assertSame(view, MetricsView.fromWire(view.wire()));
            for (MetricsDimension d : view.dimensions()) {
                assertSame(d, view.dimension(d.wire()));
                assertSame(d, view.filterField(d.wire()));
            }
            for (MetricsMeasure m : view.measures()) {
                assertSame(m, view.measure(m.wire()));
            }
        }
        Map<String, List<String>> dimensions = Map.of(
                "traces", List.of("call_site", "name", "user", "session", "outcome"),
                "llm_calls", List.of("call_site", "model", "cost_source"),
                "tool_calls", List.of("tool", "error_type", "scope", "tool_type"),
                "cases", List.of("detector", "call_site", "state", "resolution", "disposition"));
        Map<String, List<String>> measures = Map.of(
                "traces",
                List.of(
                        "traces",
                        "errored_traces",
                        "cost_usd",
                        "usd_per_trace",
                        "unpriced_traces",
                        "unsettled_traces",
                        "priced_traces",
                        "latency_p50_ms",
                        "latency_p95_ms",
                        "latency_p99_ms",
                        "latency_n",
                        "unterminated_traces",
                        "rootless_traces"),
                "llm_calls",
                List.of(
                        "calls",
                        "tokens",
                        "cost_usd",
                        "usd_per_trace",
                        "unpriced_calls",
                        "unsettled_calls",
                        "priced_traces",
                        "spending_traces"),
                "tool_calls",
                List.of("calls", "retries", "latency_p50_ms", "latency_p95_ms", "latency_p99_ms", "latency_n"),
                "cases",
                List.of("cases", "open_cases", "resolved_cases"));
        assertEquals(
                List.of("traces", "llm_calls", "tool_calls", "cases"),
                List.of(MetricsView.values()).stream().map(MetricsView::wire).toList());
        for (MetricsView view : MetricsView.values()) {
            assertEquals(
                    dimensions.get(view.wire()),
                    view.dimensions().stream().map(MetricsDimension::wire).toList());
            assertEquals(
                    measures.get(view.wire()),
                    view.measures().stream().map(MetricsMeasure::wire).toList());
        }
    }

    /** The bug: a refusal that names the wrong code or omits the view leaves the caller unable to fix the query. */
    @Test
    @DisplayName("unknown names throw their own code and name the view")
    void unknownNamesThrowTheRightCodeAndNameTheView() {
        assertRefused(
                MetricsError.UNKNOWN_VIEW,
                "No metrics view named 'spans'. Views: traces, llm_calls, tool_calls, cases",
                () -> MetricsView.fromWire("spans"));
        assertRefused(
                MetricsError.UNKNOWN_MEASURE,
                "View 'traces' has no measure named 'tokens'",
                () -> MetricsView.TRACES.measure("tokens"));
        assertRefused(
                MetricsError.UNKNOWN_DIMENSION,
                "View 'llm_calls' has no dimension named 'tool'",
                () -> MetricsView.LLM_CALLS.dimension("tool"));
        assertRefused(
                MetricsError.UNKNOWN_FILTER_FIELD,
                "View 'cases' has no dimension named 'model' to filter on",
                () -> MetricsView.CASES.filterField("model"));
    }

    /** The bug: a lateral root join on every query, or a latency read with no root to read. */
    @Test
    @DisplayName("only the six root-reading measures of traces need the root lateral")
    void needsRootIsTrueOnlyForTheSixRootMeasuresOfTraces() {
        assertEquals(
                Set.of(
                        "traces.latency_p50_ms",
                        "traces.latency_p95_ms",
                        "traces.latency_p99_ms",
                        "traces.latency_n",
                        "traces.unterminated_traces",
                        "traces.rootless_traces"),
                flagged(true));
    }

    /**
     * The bug: an llm_calls figure with no trace to read is_settled from, so a call in a trace still settling counts
     * in one figure and not in the one beside it; or a trace join on a view that has no trace_id to join on.
     */
    @Test
    @DisplayName("every llm_calls measure needs the trace join, and no other view's does")
    void needsTraceIsTrueForEveryLlmCallsMeasureOnly() {
        assertEquals(
                Set.of(
                        "llm_calls.calls",
                        "llm_calls.tokens",
                        "llm_calls.cost_usd",
                        "llm_calls.usd_per_trace",
                        "llm_calls.unpriced_calls",
                        "llm_calls.unsettled_calls",
                        "llm_calls.priced_traces",
                        "llm_calls.spending_traces"),
                flagged(false));
    }

    /**
     * The bug: a trace-joined measure that reads a span column the dimensioned query's base CTE does not carry, or a
     * trace column other than is_settled, so every table and by-key chart naming it fails with an unknown column.
     */
    @Test
    @DisplayName("a trace-joined measure reads only carried span columns and t.is_settled")
    void aTraceJoinedMeasureReadsOnlyCarriedColumns() {
        Pattern column = Pattern.compile("\\b[st]\\.[a-z_]+");
        for (MetricsView view : MetricsView.values()) {
            for (MetricsMeasure m : view.measures()) {
                if (!m.needsTrace()) continue;
                Matcher read = column.matcher(m.sql());
                while (read.find()) {
                    String c = read.group();
                    assertTrue(
                            c.equals(MetricsSql.SETTLED) || view.carried().contains(c),
                            view.wire() + "." + m.wire() + " reads " + c + ", which its base CTE does not carry");
                }
            }
            assertTrue(view.carried().stream().allMatch(c -> c.startsWith("s.")), view.wire() + " " + view.carried());
        }
    }

    /** The bug: container spans summed beside their children, so every subtree total counts twice. */
    @Test
    @DisplayName("the llm_calls gate keeps live LLM spans only")
    void llmCallsGateScopesToLlmSpans() {
        assertEquals("s.project_id = :pid AND s.kind = 'llm' AND NOT s.is_deleted", MetricsView.LLM_CALLS.gate());
    }

    /** The bug: trace-level tokens summed over a trace's spans, which already roll their children up. */
    @Test
    @DisplayName("traces declares no token measure")
    void tracesDeclaresNoTokenMeasure() {
        for (MetricsMeasure m : MetricsView.TRACES.measures()) {
            assertTrue(
                    m.unit() != MetricsMeasure.Unit.TOKENS && !m.wire().contains("token"),
                    "traces must not count tokens: " + m.wire());
        }
    }

    /** The bug: a companion the planner cannot emit, so every answer naming its measure fails. */
    @Test
    @DisplayName("every companion is a measure of its own view")
    void everyCompanionIsAMeasureOfItsView() {
        for (MetricsView view : MetricsView.values()) {
            Set<String> wires = new HashSet<>();
            view.measures().forEach(m -> wires.add(m.wire()));
            for (MetricsMeasure m : view.measures()) {
                for (String companion : m.companions()) {
                    assertTrue(
                            wires.contains(companion),
                            view.wire() + "." + m.wire() + " names a companion it lacks: " + companion);
                }
            }
        }
    }

    /** The bug: three sorts for one latency family, one per percentile. */
    @Test
    @DisplayName("the latency family shares one alias and reads indexes 0, 1 and 2 of one expression")
    void latencyMeasuresShareOneAliasWithIndexesZeroOneTwo() {
        for (MetricsView view : List.of(MetricsView.TRACES, MetricsView.TOOL_CALLS)) {
            List<MetricsMeasure> family = List.of(
                    view.measure("latency_p50_ms"), view.measure("latency_p95_ms"), view.measure("latency_p99_ms"));
            List<Integer> indexes = new ArrayList<>();
            for (MetricsMeasure m : family) {
                assertEquals("latency_q", m.alias(), view.wire() + "." + m.wire());
                assertEquals(family.get(0).sql(), m.sql(), view.wire() + "." + m.wire());
                assertTrue(m.quantile());
                indexes.add(Objects.requireNonNull(m.arrayIndex()));
            }
            assertEquals(List.of(0, 1, 2), indexes);
            assertEquals(
                    "(" + family.get(0).sql() + ")[2]",
                    view.measure("latency_p95_ms").rankSql());
        }
        MetricsMeasure scalar = MetricsView.TRACES.measure("traces");
        assertEquals("count(*)", scalar.rankSql());
        assertEquals("traces", scalar.alias());
    }

    /** The bug: a timestamptz bound compared with the cases view's text clock, or a text bound on the others. */
    @Test
    @DisplayName("only cases places its rows on a text clock")
    void onlyCasesHasATextClock() {
        for (MetricsView view : MetricsView.values()) {
            assertEquals(view == MetricsView.CASES, view.textClock(), view.wire());
        }
    }

    /** The bug: two measures under one wire name, so one shadows the other in the answer's value map. */
    @Test
    @DisplayName("wire names are unique within a view and snake_case")
    void wireNamesAreUniquePerViewAndSnakeCase() {
        for (MetricsView view : MetricsView.values()) {
            List<String> names = new ArrayList<>();
            view.dimensions().forEach(d -> names.add("dimension " + d.wire()));
            view.measures().forEach(m -> names.add("measure " + m.wire()));
            assertEquals(names.size(), new HashSet<>(names).size(), view.wire() + " repeats a name: " + names);
            assertTrue(SNAKE_CASE.matcher(view.wire()).matches(), view.wire());
            for (String name : names) {
                String wire = name.substring(name.indexOf(' ') + 1);
                assertTrue(SNAKE_CASE.matcher(wire).matches(), view.wire() + " " + name);
            }
        }
    }

    private static Set<String> flagged(boolean root) {
        Set<String> out = new HashSet<>();
        for (MetricsView view : MetricsView.values()) {
            for (MetricsMeasure m : view.measures()) {
                if (root ? m.needsRoot() : m.needsTrace()) out.add(view.wire() + "." + m.wire());
            }
        }
        return out;
    }

    private static void assertRefused(MetricsError code, String message, Executable call) {
        TessaryException ex = assertThrows(TessaryException.class, call);
        assertEquals(code, ex.error());
        assertEquals(message, ex.getMessage());
    }
}
