// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricsSqlTest {

    /** The bug: a root pick that drifts from ix_span_root's predicate and order makes every latency read hit the heap. */
    @Test
    @DisplayName("the root lateral is spelled as ix_span_root is, left-joined on the trace alias t")
    void rootLateralIsSpelledAsIxSpanRootIs() {
        assertEquals("""
                LEFT JOIN LATERAL (
                    SELECT s.started_at, s.ended_at
                      FROM span s
                     WHERE s.project_id = t.project_id AND s.trace_id = t.id
                       AND s.parent_span_id IS NULL AND s.is_deleted IS NOT TRUE
                     ORDER BY s.started_at ASC, s.id ASC
                     LIMIT 1) root ON TRUE
                """, MetricsSql.ROOT_LATERAL);
    }

    /**
     * The bug: a dimension that groups NULL apart from the sentinel splits one bucket in two, and one that sorts by the
     * database collation orders keys differently from the frontend.
     */
    @Test
    @DisplayName("every dimension sorts bytewise, and every nullable one coalesces to a named fallback")
    void unattributedIsTheSharedSentinelAndEveryNullableDimensionCoalescesToAFallbackUnderC() {
        assertEquals("__unattributed__", MetricsSql.UNATTRIBUTED);
        assertEquals(
                "COALESCE(t.call_site_id, '__unattributed__') COLLATE \"C\"",
                MetricsSql.unattributed("t.call_site_id"));
        Set<String> notNullable = Set.of("outcome", "detector", "state", "cost_source");
        for (MetricsView view : MetricsView.values()) {
            for (MetricsDimension d : view.dimensions()) {
                String where = view.wire() + "." + d.wire();
                assertTrue(d.sql().endsWith(" COLLATE \"C\""), where + " must sort bytewise: " + d.sql());
                assertEquals(
                        !notNullable.contains(d.wire()),
                        d.sql().startsWith("COALESCE("),
                        where + " coalesces exactly when its column is nullable: " + d.sql());
            }
        }
    }

    /** The bug: a tool expression the 0046 statistics do not cover leaves the planner guessing 0.5% of calls match. */
    @Test
    @DisplayName("the tool name is the expression the 0046 statistics cover")
    void toolCallNameMatchesTheStatisticsExpressionOf0046() {
        assertEquals("COALESCE(NULLIF(tc.name, ''), 'unnamed')", MetricsSql.TOOL_CALL_NAME);
    }

    /** The bug: a turn duration that disagrees with the classifier chart's, or a TURN_ENDED that breaks a FILTER. */
    @Test
    @DisplayName("turn duration and the finished-turn test match the classifier chart's, without a leading AND")
    void turnMsAndTurnEndedMatchTheClassifierChartOriginals() {
        assertEquals("EXTRACT(EPOCH FROM (root.ended_at - root.started_at))::float8 * 1000", MetricsSql.TURN_MS);
        assertEquals("root.ended_at IS NOT NULL AND root.ended_at >= root.started_at", MetricsSql.TURN_ENDED);
    }

    /** The bug: one sort per percentile triples the cost of the latency family. */
    @Test
    @DisplayName("quantiles is one percentile_cont over p50, p95 and p99 with its own FILTER")
    void quantilesBuildOnePercentileContOverThreeFractions() {
        assertEquals(
                "percentile_cont(ARRAY[0.5, 0.95, 0.99]) WITHIN GROUP (ORDER BY x) FILTER (WHERE x IS NOT NULL)",
                MetricsSql.quantiles("x", "x IS NOT NULL"));
    }

    /**
     * The bug: a bare "...T00:00:00Z" bound sorts above "...T00:00:00.123Z" as text, so a cases window drops its first
     * second and takes the first second after its end.
     */
    @Test
    @DisplayName("a text-clock bound always carries nine fraction digits in UTC")
    void textClockFormatsNineFractionDigitsInUtc() {
        List<String> formatted = List.of(
                MetricsSql.TEXT_CLOCK.format(Instant.parse("2026-10-11T00:00:00Z")),
                MetricsSql.TEXT_CLOCK.format(Instant.parse("2026-10-11T00:00:00.500Z")),
                MetricsSql.TEXT_CLOCK.format(Instant.parse("2026-10-11T23:59:59.000000001Z")));
        assertEquals(
                List.of(
                        "2026-10-11T00:00:00.000000000Z",
                        "2026-10-11T00:00:00.500000000Z",
                        "2026-10-11T23:59:59.000000001Z"),
                formatted);
    }
}
