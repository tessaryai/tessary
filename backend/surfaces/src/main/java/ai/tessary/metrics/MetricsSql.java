// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** SQL fragments the catalog and the planner share. Every one is a constant: nothing from a request reaches SQL here. */
public final class MetricsSql {

    /** The sentinel the triage views and ix_trace_scope_settled_started share for a trace with no call site. */
    public static final String UNATTRIBUTED = "__unattributed__";

    /** The fold key for keys past top_n. */
    public static final String OTHER = "__other__";

    /**
     * The first parentless span of a trace, spelled as ix_span_root (0045) is so the index alone answers it. The
     * classifier chart's root join, made LEFT on the trace alias t so a rootless trace keeps its row.
     */
    public static final String ROOT_LATERAL = """
            LEFT JOIN LATERAL (
                SELECT s.started_at, s.ended_at
                  FROM span s
                 WHERE s.project_id = t.project_id AND s.trace_id = t.id
                   AND s.parent_span_id IS NULL AND s.is_deleted IS NOT TRUE
                 ORDER BY s.started_at ASC, s.id ASC
                 LIMIT 1) root ON TRUE
            """;

    /** Joined only when a settled measure is asked, so a plain call count stays an index-only span scan. */
    public static final String TRACE_JOIN = "JOIN trace t ON t.project_id = s.project_id AND t.id = s.trace_id";

    /** The one trace column a trace-joined measure reads. */
    public static final String SETTLED = "t.is_settled";

    /**
     * Puts the settle flag a base CTE carried back under the trace alias, so a measure reads {@link #SETTLED} from
     * {@code base s} unchanged. Postgres flattens it: the plan has no node for it.
     */
    public static final String SETTLED_LATERAL = "CROSS JOIN LATERAL (SELECT s.is_settled) t";

    public static final String TURN_MS = "EXTRACT(EPOCH FROM (root.ended_at - root.started_at))::float8 * 1000";

    /** No leading AND, so it fits a FILTER clause. */
    public static final String TURN_ENDED = "root.ended_at IS NOT NULL AND root.ended_at >= root.started_at";

    /** The 0046 statistics expression (st_tool_call_tool_name), so the planner can estimate a tool filter. */
    public static final String TOOL_CALL_NAME = "COALESCE(NULLIF(tc.name, ''), 'unnamed')";

    /** One sort yields the family; p50 is index 0, p95 index 1, p99 index 2. */
    public static final String QUANTILES = "ARRAY[0.5, 0.95, 0.99]";

    /**
     * How a text-clock bound (the cases view) is bound. Instant.toString() drops zero fraction groups, so a case opened
     * at 00:00:00.123Z sorts below the bare bound "...T00:00:00Z" ('.' is 0x2E, 'Z' is 0x5A) and a whole-hour window
     * would miss its first second and take the first second after its end. Nine fraction digits put the bound below
     * every stored value of the same instant and above every earlier one, so the window is exact.
     */
    public static final DateTimeFormatter TEXT_CLOCK = DateTimeFormatter.ofPattern(
                    "uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private MetricsSql() {}

    /** Groups as the database collation does and sorts bytewise, so the order matches the frontend's. */
    public static String collate(String expr) {
        return expr + " COLLATE \"C\"";
    }

    public static String unattributed(String column) {
        return collate("COALESCE(" + column + ", '" + UNATTRIBUTED + "')");
    }

    public static String quantiles(String value, String present) {
        return "percentile_cont(" + QUANTILES + ") WITHIN GROUP (ORDER BY " + value + ") FILTER (WHERE " + present
                + ")";
    }
}
