// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A NULL rollup means "not yet rolled up", distinct from zero, so rollup columns come back beside the leaf facts. The
 * call site is read from {@code trace.call_site_id}, never re-derived, so this and
 * {@link ai.tessary.classifier.substrate.BehaviorSubstrateRepository} can't disagree about a trace's bucket.
 */
@Repository
public class MetricSourceRepository {

    /**
     * Container kinds are excluded because their duration tracks the root's and would mask turn-duration shifts.
     * {@code llm} is excluded because the tool bucket key has no call site, so it would pool every LLM call in the
     * project.
     */
    private static final List<String> MEASURED_KINDS = List.of("tool", "mcp", "retrieval", "embedding", "reranker");

    private final JdbcClient jdbc;

    public MetricSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Duration is the root span's own interval, never an envelope (so not {@code trace.latency_ms}): async children can
     * outlive their parent. The root pick matches {@code VitalsRepository}'s {@code DISTINCT ON}. An unterminated root
     * is flagged, not dropped. LEFT LATERAL keeps one row per trace even before its spans land.
     */
    public List<TurnFacts> turnFacts(String projectId, List<String> traceIds) {
        return jdbc.sql("""
                        SELECT tr.id                                             AS trace_id,
                               tr.total_cost                                     AS rollup_total_cost,
                               -- Carried beside the total because SUM skips nulls: a trace with one
                               -- unpriced generation reports the price of the others and reads cheap.
                               tr.unpriced_spans                                 AS unpriced_spans,
                               (root.id IS NOT NULL)                             AS has_root,
                               (root.id IS NOT NULL AND root.ended_at IS NULL)    AS root_unterminated,
                               EXTRACT(EPOCH FROM (root.ended_at - root.started_at)) * 1000 AS root_ms,
                               -- What the user asked for, carried so a finding can print flat inputs
                               -- beside moved outputs, read from the same row set as the measure.
                               --
                               -- Length of the entry point's own input, read from the payload rather
                               -- than a preview (previews cap at 200 characters and would saturate).
                               -- Bounded 1:1 join to one page's root spans. NULL means the payload
                               -- aged out: unknown, never empty.
                               length(rootpl.input)                              AS user_msg_chars,
                               -- Thread depth: how many turns of this conversation started before this one.
                               (SELECT count(*) FROM trace prior
                                 WHERE prior.project_id = tr.project_id
                                   AND prior.is_deleted IS NOT TRUE
                                   AND COALESCE(prior.session_id, prior.id) = COALESCE(tr.session_id, tr.id)
                                   AND (prior.started_at, prior.id) < (tr.started_at, tr.id)) AS prior_turns
                        FROM trace tr
                          LEFT JOIN LATERAL (
                              SELECT s.id, s.trace_id, s.started_at, s.ended_at
                              FROM span s
                              WHERE s.project_id = tr.project_id
                                AND s.trace_id = tr.id
                                AND s.parent_span_id IS NULL
                                AND s.is_deleted IS NOT TRUE
                              -- Earliest-starting root; started_at is never null, so the id is the
                              -- only tiebreak needed.
                              ORDER BY s.started_at ASC, s.id ASC
                              LIMIT 1
                          ) root ON TRUE
                          LEFT JOIN span_payload rootpl
                            ON rootpl.project_id = tr.project_id AND rootpl.trace_id = root.trace_id
                           AND rootpl.span_id = root.id
                        WHERE tr.project_id = :pid AND tr.id IN (:ids) AND tr.is_deleted IS NOT TRUE
                        """)
                .param("pid", projectId)
                .param("ids", traceIds)
                .query((rs, n) -> new TurnFacts(
                        rs.getString("trace_id"),
                        rs.getBigDecimal("rollup_total_cost"),
                        nullableInt(rs, "unpriced_spans"),
                        rs.getBoolean("has_root"),
                        rs.getBoolean("root_unterminated"),
                        nullableDouble(rs, "root_ms"),
                        nullableLong(rs, "user_msg_chars"),
                        nullableLong(rs, "prior_turns")))
                .list();
    }

    /**
     * Scoped to {@code kind = 'llm'}: an {@code agent} span carries its subtree's cumulative usage, so summing across
     * kinds would roughly double every figure. A null bucket means "reported nothing", distinct from 0.
     */
    public List<LeafUsage> leafUsage(String projectId, List<String> traceIds) {
        return jdbc.sql("""
                        SELECT s.trace_id            AS trace_id,
                               s.provided_model_name AS model,
                               s.input_tokens        AS input_tokens,
                               s.output_tokens       AS output_tokens,
                               s.cache_read_tokens   AS cache_read_tokens,
                               s.cache_write_tokens  AS cache_write_tokens,
                               s.total_cost          AS total_cost,
                               s.cost_source         AS cost_source
                        FROM span s
                        WHERE s.project_id = :pid
                          AND s.trace_id IN (:ids)
                          AND s.kind = 'llm'
                          AND s.is_deleted IS NOT TRUE
                          AND s.total_tokens IS NOT NULL
                        """)
                .param("pid", projectId)
                .param("ids", traceIds)
                .query((rs, n) -> new LeafUsage(
                        rs.getString("trace_id"),
                        rs.getString("model"),
                        nullableLong(rs, "input_tokens"),
                        nullableLong(rs, "output_tokens"),
                        nullableLong(rs, "cache_read_tokens"),
                        nullableLong(rs, "cache_write_tokens"),
                        rs.getBigDecimal("total_cost"),
                        rs.getString("cost_source")))
                .list();
    }

    /**
     * The bucket key is the tool's name, never the span's. The LATERAL keeps one row per span, since {@code tool_call}
     * can hold duplicates for a re-ingested span. Unended spans are flagged, not filtered: a tool that increasingly
     * hangs must not read as a shrinking sample of fast calls.
     */
    public List<ToolSpanFacts> toolSpanFacts(String projectId, List<String> traceIds) {
        return jdbc.sql("""
                        SELECT s.trace_id       AS trace_id,
                               s.id             AS span_id,
                               s.kind           AS kind,
                               COALESCE(tcn.name, pl.attributes->>'gen_ai.tool.name', s.name) AS name,
                               s.latency_ms     AS rollup_latency_ms,
                               (s.ended_at IS NULL) AS unterminated,
                               EXTRACT(EPOCH FROM (s.ended_at - s.started_at)) * 1000 AS span_ms,
                               -- The span's own start, the only clock windows are cut on: a backfill
                               -- compresses ingest time into minutes, but started_at keeps the real timeline.
                               s.started_at     AS event_at
                        FROM span s
                          LEFT JOIN span_payload pl
                            ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id
                          LEFT JOIN LATERAL (
                              SELECT tc.name
                              FROM tool_call tc
                              WHERE tc.project_id = s.project_id AND tc.trace_id = s.trace_id
                                AND tc.span_id = s.id AND tc.is_deleted IS NOT TRUE AND tc.name IS NOT NULL
                              ORDER BY tc.started_at ASC NULLS LAST, tc.created_at ASC, tc.id ASC
                              LIMIT 1
                          ) tcn ON TRUE
                        WHERE s.project_id = :pid
                          AND s.trace_id IN (:ids)
                          AND s.kind IN (:kinds)
                          AND s.is_deleted IS NOT TRUE
                        ORDER BY s.trace_id ASC, s.started_at ASC, s.id ASC
                        """)
                .param("pid", projectId)
                .param("ids", traceIds)
                .param("kinds", MEASURED_KINDS)
                .query((rs, n) -> new ToolSpanFacts(
                        rs.getString("trace_id"),
                        rs.getString("span_id"),
                        rs.getString("kind"),
                        rs.getString("name"),
                        nullableLong(rs, "rollup_latency_ms"),
                        rs.getBoolean("unterminated"),
                        nullableDouble(rs, "span_ms"),
                        eventAt(rs)))
                .list();
    }

    /**
     * @param unpricedSpans a positive value means {@code rollupTotalCost} has a hole in it and must not be read as the
     * turn's cost @param userMsgChars null is "unknown", never "empty"
     */
    public record TurnFacts(
            String traceId,
            @Nullable BigDecimal rollupTotalCost,
            @Nullable Integer unpricedSpans,
            boolean hasRoot,
            boolean rootUnterminated,
            @Nullable Double rootMillis,
            @Nullable Long userMsgChars,
            @Nullable Long priorTurns) {}

    /**
     * A null token bucket means "not reported", distinct from zero: conflating them would read a cache-read count
     * falling to zero, the regression this exists to catch, as "provider does not report cache reads".
     * {@code totalCost} is null exactly when {@code costSource} is {@code unpriced}, never zero.
     */
    public record LeafUsage(
            String traceId,
            @Nullable String model,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable BigDecimal totalCost,
            String costSource) {

        public boolean unpriced() {
            return "unpriced".equals(costSource) || totalCost == null;
        }
    }

    /** @param eventAt the span's own start: the clock windows are cut on, never the cursor's */
    public record ToolSpanFacts(
            String traceId,
            String observationId,
            @Nullable String kind,
            @Nullable String name,
            @Nullable Long rollupLatencyMs,
            boolean unterminated,
            @Nullable Double spanMillis,
            String eventAt) {}

    /**
     * Read through {@link ai.tessary.storage.Timestamps}: {@code getString} on a timestamptz renders the driver's own
     * form, which {@code Instant.parse} rejects.
     */
    private static String eventAt(ResultSet rs) throws SQLException {
        return Objects.requireNonNull(ai.tessary.storage.Timestamps.iso(rs, "event_at"));
    }

    private static @Nullable Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
