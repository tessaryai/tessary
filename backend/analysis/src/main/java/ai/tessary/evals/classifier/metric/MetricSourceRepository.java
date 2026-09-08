// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

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
 * <p><b>The rollups are real now.</b> This class used to carry a long warning that {@code trace},
 * {@code observation} and {@code context} all had {@code latency_ms} / {@code total_cost} /
 * {@code total_tokens} columns and that the v1 write path passed literal null for every one of
 * them — so a detector reading them abstained on 100% of traffic while looking correct in every unit
 * test. In v2 the rollup worker writes those columns as a wholesale replacement over the trace's spans
 * (§7.2), so {@code trace.latency_ms} and {@code trace.total_cost} are the number, not a placeholder.
 * Both are still returned beside the leaf facts rather than instead of them: the column-preferred rule
 * of {@code classifiers/metric_drift/PROGRAM.md} §3.0 lives one layer up in {@link MetricSource}, and a
 * NULL rollup still means "not yet rolled up" — a different sentence from zero, which is exactly the
 * distinction the sweep's {@code is_settled} gate exists to respect.
 *
 * <p><b>Usage and cost are typed columns, not a parsed blob.</b> The whole {@code usage jsonb} →
 * Jackson → key-spelling-presence dance is gone: {@code span} carries one column per bucket, NULL when
 * the producer reported nothing and a real value (including 0) when it did, so presence is expressible
 * without reading raw key spellings. The cache-inclusive correction that once had to be applied at read
 * time now happens at write time in {@code IngestPricer}, which is why the sum here is a plain SUM.
 *
 * <p><b>The entry-point call site is deliberately NOT resolved here.</b> The rollup worker copies it
 * onto {@code trace.call_site_id} from the root span, and
 * {@link ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository} reads that one column; the
 * sweep hands those heads straight to {@link MetricSource}. Re-deriving it would be the one place the
 * two classifiers could silently disagree about which bucket a trace belongs to — which is what the old
 * duplicated four-deep fallback chain risked every sweep.
 *
 * <p>Every read is keyed on a batch of trace ids — one round trip per sweep page, never one per trace.
 */
@Repository
public class MetricSourceRepository {

    /**
     * The span kinds whose own duration is measured as {@code tool_duration}.
     *
     * <p><b>Container kinds are excluded on purpose.</b> An {@code agent} or {@code workflow} span
     * encloses the turn, so its duration tracks the root's almost exactly — under the §6.1 suppression
     * rule it would "explain" every turn-duration shift and suppress the turn finding permanently,
     * including the one case turn duration exists to catch (an agent doing eleven tool calls where
     * three used to do, each of them individually fast).
     *
     * <p><b>{@code llm} is excluded too</b>, for a different reason: {@link
     * ai.tessary.evals.classifier.substrate.ActionSymbol} collapses every generation to the single symbol
     * {@code llm:answer}, and the tool bucket key carries no call site, so an {@code llm} bucket would
     * pool every LLM call in the project into one distribution. That is the mixture PROGRAM.md §2.4
     * rejects for {@code __unattributed__} — it moves whenever the model or route mix moves, whatever
     * the models themselves did. LLM time is watched at turn grain instead, where it is scoped to a
     * call site.
     *
     * <p>What is left is the dispatchable set: what the agent chose to call out to.
     */
    private static final List<String> MEASURED_KINDS = List.of("tool", "mcp", "retrieval", "embedding", "reranker");

    private final JdbcClient jdbc;

    public MetricSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Per-trace duration and cost facts for one page: the rollup columns, plus everything a derivation
     * from the ROOT span needs.
     *
     * <p><b>Duration comes from the root span's own interval, never from a min/max envelope over the
     * trace.</b> Measured against production the envelope inflated p95 by ~10%, because async children
     * outlive their parent; the root span encloses its children and is what Jaeger, Tempo and Datadog
     * report as trace duration. Note that {@code trace.latency_ms} IS such an envelope
     * ({@code ended_at - started_at} over the whole trace), which is exactly why both are returned and
     * {@link MetricSource} chooses — the rollup column is the right number for a spend or count measure
     * and the wrong one for turn duration.
     *
     * <p>The root LATERAL takes the earliest-STARTING parentless span, which decides the ~0.1% of traces
     * carrying more than one root (a genuine second entry point) the same way {@code
     * VitalsRepository}'s {@code DISTINCT ON} does, so the two surfaces cannot report different durations
     * for the same turn. Unlike that read it does <em>not</em> filter on {@code ended_at IS NOT NULL}: a
     * turn whose root never ended is a counted category, not a row to drop, and {@code root_unterminated}
     * is what tells the two apart.
     *
     * <p><b>The {@code parent_external_span_id IS NULL} guard is gone, because the ambiguity it guarded
     * against is gone.</b> In v1 a null {@code parent_observation_id} meant either "the producer said
     * ROOT" or "the parent has not been relinked yet", so a mid-flight trace's first-arriving child
     * impersonated the root and a 30-second turn reported the 1-second child that happened to land first
     * — silently, and biased, because the longer the turn the wider that window. In v2 {@code
     * parent_span_id} is the producer's own statement, stored verbatim and never repaired; unresolved
     * ancestry lives in a NULL {@code path} instead. A null parent now means exactly one thing.
     *
     * <p>{@code prior_turns} is the trace's rank within its conversation, by the pinned
     * {@code COALESCE(thread_id, session_id, id)} grain — the same expression conformance groups on. v1
     * read {@code context.seq}, an arrival-order counter maintained per parent; this is event order,
     * which is what "how many turns preceded this one" actually means. A single-shot producer's trace is
     * its own conversation and correctly reports 0.
     *
     * <p>Exactly one row comes back per trace in the page, root or no root — the join to {@code span} is
     * a LEFT LATERAL so a trace whose spans have not landed yet is still accounted for rather than
     * vanishing from the page.
     */
    public List<TurnFacts> turnFacts(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
        return jdbc.sql("""
                        SELECT tr.id                                             AS trace_id,
                               tr.latency_ms                                     AS rollup_latency_ms,
                               tr.total_cost                                     AS rollup_total_cost,
                               -- Carried beside the total because it is what says whether the total means
                               -- anything: SUM skips nulls, so a trace holding one generation the price
                               -- book could not price reports the price of the others and reads cheap.
                               tr.unpriced_spans                                 AS unpriced_spans,
                               (root.id IS NOT NULL)                             AS has_root,
                               (root.id IS NOT NULL AND root.ended_at IS NULL)    AS root_unterminated,
                               EXTRACT(EPOCH FROM (root.ended_at - root.started_at)) * 1000 AS root_ms,
                               -- The workload block of PROGRAM.md §7: what the USER asked for, carried so
                               -- a finding can print flat inputs beside moved outputs. Both are read from
                               -- the same row set the measure is, so they describe the same population by
                               -- construction rather than by a second query agreeing with the first.
                               --
                               -- Length of the ENTRY POINT's own input, read from the payload rather than
                               -- from a preview: previews are capped at 200 characters, so their length
                               -- saturates and reports every long message as identical. This is a bounded
                               -- 1:1 join to the root span of one page of traces — the documented
                               -- rule-5 exception, taken deliberately and only here. NULL when the payload
                               -- has aged out, which is "unknown" and never "empty".
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
                              -- Earliest-starting root. started_at is NOT NULL in v2, so the id is the only
                              -- tiebreak needed and the old created_at rung of the chain is gone with the
                              -- column that made it necessary.
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
     * Every priced leaf of the given traces — the generations cost and the token buckets sum over.
     *
     * <p><b>Scoped to {@code kind = 'llm'} deliberately.</b> An {@code agent} span carries the CUMULATIVE
     * usage of its whole subtree (measured on production: 4,015 agent rows holding 5.2B tokens against
     * 65,104 llm rows holding 6.4B), so summing across kinds roughly doubles every figure. v2's ingest
     * already refuses to store typed usage on container kinds for exactly that reason — their producer
     * numbers are kept as a receipt in {@code span_payload.provided_usage} — so this filter is now a
     * belt-and-braces restatement of a rule the schema enforces, kept because a future kind that DOES
     * report own consumption would otherwise silently join the sum.
     *
     * <p><b>Root spans carry no model at all</b>, which is why cost is never read off the root even
     * though the BUCKET is the root's entry-point call site. An llm span tagged with a call site whose
     * root span is not would land its cost in the call-site bucket and its turn in
     * {@code __unattributed__}, so the bucket would read "spend, zero turns" and could never flag. The
     * trace id is what joins the two back together.
     *
     * <p><b>NULL is the presence signal.</b> Each bucket comes back null when the producer reported
     * nothing for it and a value — including 0 — when it did. That distinction used to require reading
     * raw key spellings out of a jsonb blob, because the normalizer collapsed an absent key to 0 and
     * "reported zero cache writes" and "reports no cache writes at all" arrived identical. The typed
     * columns say it directly.
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
     * Every dispatchable span of the given traces, with its own interval — the {@code tool_duration}
     * subjects. See {@link #MEASURED_KINDS} for which kinds qualify and why the containers do not.
     *
     * <p>The name resolution is {@code BehaviorSubstrateRepository.actionsForTraces}' verbatim, and has
     * to stay that way: the tool bucket key is an {@link ai.tessary.evals.classifier.substrate.ActionSymbol},
     * so a different name here would key latency on a symbol drift's alphabet never mints. Three sources
     * in the ingestion contract's precedence — the normalized {@code tool_call.name}, then the raw
     * {@code gen_ai.tool.name} attribute from the span payload, then the span name. The LATERAL is what
     * keeps it one row per span: {@code tool_call} is unique only on {@code (span_id, source_external_id)}
     * and that column is nullable, so a re-ingest can leave two rows and a plain join would count the
     * same span's duration twice.
     *
     * <p>Spans whose {@code ended_at} is null come back too, flagged rather than filtered, for the same
     * reason unfinished turns do: a tool that increasingly hangs must not read as a shrinking sample of
     * fast calls.
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
                               -- The span's OWN start, and in v2 the only clock it has: windows are cut on
                               -- event time because a backfill compresses ingest time into minutes while
                               -- started_at keeps the agent's real timeline.
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
     * @param rollupLatencyMs {@code trace.latency_ms} — the rollup worker's envelope over the whole
     *     trace, or null when it has not rolled up yet
     * @param rollupTotalCost {@code trace.total_cost} — likewise
     * @param unpricedSpans how many of the trace's spans carried usage the price book could not price.
     *     Null when the trace has not rolled up; a positive value means {@code rollupTotalCost} is a sum
     *     with a hole in it and must not be read as the turn's cost
     * @param hasRoot whether any parentless span of this trace has landed at all
     * @param rootUnterminated the root span exists but never ended — the turn did not complete
     * @param rootMillis the root span's own {@code ended_at - started_at} in ms, or null when either
     *     endpoint is missing
     * @param userMsgChars characters in the root span's own stored input, or null when the producer
     *     shipped none or the payload has aged out. Null is "unknown", never "empty" — see
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
     * apart reads a cache-read count falling to zero — the prompt-prefix regression this program exists
     * to catch — as "this provider does not report cache reads".
     *
     * @param model the producer's raw model string, kept for logging and for the cache-write gate
     * @param modelId the resolved {@code model.id}, or null when the price book knew no such model
     * @param totalCost the generated per-span total, priced at write time against the book in force
     *     then. Null exactly when {@code costSource} is {@code unpriced} — never zero, which would
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
     * @param eventAt the span's own start as an ISO-8601 instant — the clock windows are cut on, never
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
     * {@code event_at} as an ISO-8601 instant, read through {@link ai.tessary.evals.storage.Timestamps}
     * rather than {@code getString} — on a {@code timestamptz} the latter renders the driver's own form
     * ({@code 2026-07-28 18:46:37.416851+05:30}: space separator, JVM-zone offset) that
     * {@code Instant.parse} rejects outright, and getting it wrong is silent.
     */
    private static String eventAt(ResultSet rs) throws SQLException {
        String iso = ai.tessary.evals.storage.Timestamps.iso(rs, "event_at");
        if (iso == null) {
            // Unreachable: span.started_at is NOT NULL. Failing loudly rather than returning null keeps
            // the non-null contract on ToolSpanFacts.eventAt honest — a null there would silently make
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
