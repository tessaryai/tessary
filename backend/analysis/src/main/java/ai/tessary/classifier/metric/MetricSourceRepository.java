// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The substrate read surface for the metric-drift sweep: the raw facts a measure is computed from,
 * for one page of traces, and nothing else.
 *
 * <p>Duration, cost, and token usage are typed columns on {@code span}/{@code trace}, NULL when a
 * producer reported nothing and a real value (including 0) when it did, so presence never needs
 * reading out of raw key spellings. The rollup columns ({@code trace.latency_ms}, {@code
 * total_cost}) are returned beside the leaf facts rather than instead of them, since a NULL rollup
 * means "not yet rolled up," distinct from zero.
 *
 * <p>The entry-point call site is never re-derived here: the rollup worker copies it onto {@code
 * trace.call_site_id} from the root span, and this and {@link
 * ai.tessary.classifier.substrate.BehaviorSubstrateRepository} both read that one column, so the
 * two classifiers can't disagree about which bucket a trace belongs to.
 *
 * <p>Every read is keyed on a batch of trace ids: one round trip per sweep page, never one per trace.
 */
@Repository
public class MetricSourceRepository {

    /**
     * The span kinds whose own duration is measured as {@code tool_duration}.
     *
     * <p>Container kinds ({@code agent}, {@code workflow}) are excluded: their duration tracks the
     * root's almost exactly, so including them would suppress every turn-duration shift they
     * enclose, including the case turn duration exists to catch.
     *
     * <p>{@code llm} is excluded too: the tool bucket key carries no call site, so an {@code llm}
     * bucket would pool every LLM call in the project into one distribution that moves whenever the
     * model or route mix moves. LLM time is watched at turn grain instead, scoped to a call site.
     *
     * <p>What's left is the dispatchable set: what the agent chose to call out to.
     */
    private static final List<String> MEASURED_KINDS = List.of("tool", "mcp", "retrieval", "embedding", "reranker");

    private final JdbcClient jdbc;

    public MetricSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Per-trace duration and cost facts for one page: the rollup columns, plus everything a
     * derivation from the root span needs.
     *
     * <p>Duration comes from the root span's own interval, never a min/max envelope over the trace:
     * async children can outlive their parent and inflate an envelope's p95, while the root span
     * encloses its children the way Jaeger, Tempo, and Datadog report trace duration. {@code
     * trace.latency_ms} is such an envelope, which is why both are returned and {@link MetricSource}
     * picks the right one per measure.
     *
     * <p>The root LATERAL takes the earliest-starting parentless span, matching {@code
     * VitalsRepository}'s {@code DISTINCT ON} so the two surfaces never report different durations
     * for the same turn. It does not filter on {@code ended_at IS NOT NULL}: an unterminated root is
     * a counted category, flagged by {@code root_unterminated}, not a row to drop.
     *
     * <p>{@code parent_span_id} is the producer's own statement, stored verbatim and never repaired,
     * so a null parent means exactly one thing: this span is the root.
     *
     * <p>{@code prior_turns} is the trace's rank within its conversation by event order, on the
     * pinned {@code COALESCE(thread_id, session_id, id)} grain, the same expression conformance
     * groups on. A single-shot producer's trace is its own conversation and reports 0.
     *
     * <p>Exactly one row per trace in the page: the join to {@code span} is a LEFT LATERAL, so a
     * trace whose spans haven't landed yet is still accounted for rather than vanishing from the
     * page.
     */
    public List<TurnFacts> turnFacts(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
        return jdbc.sql("""
                        SELECT tr.id                                             AS trace_id,
                               tr.latency_ms                                     AS rollup_latency_ms,
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
                                   AND COALESCE(prior.thread_id, prior.session_id, prior.id)
                                       = COALESCE(tr.thread_id, tr.session_id, tr.id)
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
                        nullableLong(rs, "rollup_latency_ms"),
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
     * Every priced leaf of the given traces: generation cost and the token buckets it sums over.
     *
     * <p>Scoped to {@code kind = 'llm'} deliberately: an {@code agent} span carries the cumulative
     * usage of its whole subtree, so summing across kinds would roughly double every figure. The
     * schema already refuses to store typed usage on container kinds for that reason; this filter is
     * a belt-and-braces restatement in case a future kind reports its own consumption.
     *
     * <p>Root spans carry no model, so cost is never read off the root even though the bucket is the
     * root's entry-point call site; the trace id is what joins an llm span's cost back to its
     * turn's bucket.
     *
     * <p>Each token bucket comes back null when the producer reported nothing for it, and a value
     * (including 0) when it did, so "reported zero" and "reports nothing" stay distinguishable.
     */
    public List<LeafUsage> leafUsage(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
        return jdbc.sql("""
                        SELECT s.trace_id            AS trace_id,
                               s.provided_model_name AS model,
                               s.model_id            AS model_id,
                               s.input_tokens        AS input_tokens,
                               s.output_tokens       AS output_tokens,
                               s.cache_read_tokens   AS cache_read_tokens,
                               s.cache_write_tokens  AS cache_write_tokens,
                               s.reasoning_tokens    AS reasoning_tokens,
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
                        rs.getString("model_id"),
                        nullableLong(rs, "input_tokens"),
                        nullableLong(rs, "output_tokens"),
                        nullableLong(rs, "cache_read_tokens"),
                        nullableLong(rs, "cache_write_tokens"),
                        nullableLong(rs, "reasoning_tokens"),
                        rs.getBigDecimal("total_cost"),
                        rs.getString("cost_source")))
                .list();
    }

    /**
     * Every dispatchable span of the given traces, with its own interval: the {@code tool_duration}
     * subjects. See {@link #MEASURED_KINDS} for which kinds qualify.
     *
     * <p>Name resolution mirrors {@code BehaviorSubstrateRepository.actionsForTraces} exactly, since
     * the tool bucket key is an {@link ai.tessary.classifier.substrate.ActionSymbol} and a different
     * name here would key latency on a symbol the drift alphabet never mints: normalized {@code
     * tool_call.name}, then the raw {@code gen_ai.tool.name} attribute, then the span name. The
     * LATERAL keeps it one row per span, since {@code tool_call} can hold duplicate rows for a
     * re-ingested span.
     *
     * <p>Spans whose {@code ended_at} is null come back too, flagged rather than filtered: a tool
     * that increasingly hangs must not read as a shrinking sample of fast calls.
     */
    public List<ToolSpanFacts> toolSpanFacts(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
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
     * One trace's duration and cost facts, column and derivation side by side.
     *
     * @param rollupLatencyMs {@code trace.latency_ms}: the rollup worker's envelope over the whole
     *     trace, or null when it has not rolled up yet
     * @param rollupTotalCost {@code trace.total_cost}: likewise
     * @param unpricedSpans how many of the trace's spans carried usage the price book could not price.
     *     Null when the trace has not rolled up; a positive value means {@code rollupTotalCost} is a sum
     *     with a hole in it and must not be read as the turn's cost
     * @param hasRoot whether any parentless span of this trace has landed at all
     * @param rootUnterminated the root span exists but never ended: the turn did not complete
     * @param rootMillis the root span's own {@code ended_at - started_at} in ms, or null when either
     *     endpoint is missing
     * @param userMsgChars characters in the root span's own stored input, or null when the producer
     *     shipped none or the payload has aged out. Null is "unknown", never "empty"; see
     *     {@link MetricWorkload#add}.
     * @param priorTurns how many turns of this conversation started before this one, ranked on event
     *     time over the pinned conversation grain. Never null: a trace that belongs to no thread and no
     *     session is its own conversation and honestly reports 0.
     */
    public record TurnFacts(
            String traceId,
            @Nullable Long rollupLatencyMs,
            @Nullable BigDecimal rollupTotalCost,
            @Nullable Integer unpricedSpans,
            boolean hasRoot,
            boolean rootUnterminated,
            @Nullable Double rootMillis,
            @Nullable Long userMsgChars,
            @Nullable Long priorTurns) {}

    /**
     * One generation's reported usage, joined back to its turn by {@code traceId}.
     *
     * <p>Every token bucket is nullable and the null is load-bearing: it means the producer reported no
     * such count, which is a different sentence from reporting zero. A measure that cannot tell those
     * apart reads a cache-read count falling to zero, the prompt-prefix regression this program
     * exists to catch, as "this provider does not report cache reads".
     *
     * @param model the producer's raw model string, kept for logging and for the cache-write gate
     * @param modelId the resolved {@code model.id}, or null when the price book knew no such model
     * @param totalCost the generated per-span total, priced at write time against the book in force
     *     then. Null exactly when {@code costSource} is {@code unpriced}: never zero, which would
     *     launder "we hold no rate" into "it was free".
     * @param costSource {@code provided} | {@code inferred} | {@code unpriced}, written explicitly at
     *     ingest because it is the only correct way to interpret a null cost
     */
    public record LeafUsage(
            String traceId,
            @Nullable String model,
            @Nullable String modelId,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            @Nullable BigDecimal totalCost,
            String costSource) {

        /** True when we hold no rate for this generation, so its dollars are unknown rather than zero. */
        public boolean unpriced() {
            return "unpriced".equals(costSource) || totalCost == null;
        }
    }

    /**
     * One dispatchable span's duration facts.
     *
     * @param eventAt the span's own start as an ISO-8601 instant: the clock windows are cut on, never
     *     the cursor's
     */
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
     * {@code event_at} as an ISO-8601 instant, read through {@link ai.tessary.storage.Timestamps}
     * rather than {@code getString}: on a {@code timestamptz} the latter renders the driver's own form
     * ({@code 2026-07-28 18:46:37.416851+05:30}: space separator, JVM-zone offset) that
     * {@code Instant.parse} rejects outright, and getting it wrong is silent.
     */
    private static String eventAt(ResultSet rs) throws SQLException {
        String iso = ai.tessary.storage.Timestamps.iso(rs, "event_at");
        if (iso == null) {
            // Unreachable: span.started_at is NOT NULL. Failing loudly rather than returning null keeps
            // the non-null contract on ToolSpanFacts.eventAt honest; a null there would silently make
            // every window comparison fall through to a wall-clock default.
            throw new SQLException("event_at resolved to null for span " + rs.getString("span_id"));
        }
        return iso;
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
