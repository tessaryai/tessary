// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static ai.tessary.storage.SessionRepository.requireIso;
import static ai.tessary.storage.TraceV2Repository.intOrNull;
import static ai.tessary.storage.TraceV2Repository.longOrNull;
import static ai.tessary.storage.TraceV2Repository.numericOrNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for the v2 {@code span} table (substrate-model.md §3, §6.2).
 *
 * <p>One write shape: the last-write-wins upsert. A span is exported when it ends, but partial versions
 * do arrive, a streaming span flushed early, a redelivered batch, a producer that emits twice, and a
 * completed version must be able to replace a partial one. Versioning is by {@code event_ts}.
 *
 * <p>Reads are through the primary-key prefix {@code (project_id, trace_id)}, which is also the physical
 * clustering, so "this trace's spans" is one indexed, sequential read.
 *
 */
@Repository
public class SpanRepository {

    private static final String COLS = "project_id, trace_id, id, parent_span_id, path::text AS path, depth, "
            + "session_id, user_id, project_version_id, call_site_id, trace_name, kind, name, "
            + "is_logical_root, status, level, error_type, error_message, started_at, ended_at, latency_ms, ttft_ms, "
            + "provided_model_name, model_id, input_tokens, output_tokens, cache_read_tokens, "
            + "cache_write_tokens, reasoning_tokens, total_tokens, input_cost, output_cost, cache_read_cost, "
            + "cache_write_cost, total_cost, cost_source, price_book_version, input_preview, output_preview, "
            + "correlation_state, path_state, event_ts, is_deleted, created_at";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    public SpanRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private static final String UPSERT_SQL = """
                        INSERT INTO span (project_id, trace_id, id, parent_span_id, path, session_id, user_id,
                                          project_version_id, call_site_id, trace_name, kind,
                                          name, is_logical_root, status, level, error_type, error_message,
                                          started_at, ended_at,
                                          latency_ms, ttft_ms, provided_model_name, model_id, input_tokens,
                                          output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens,
                                          input_cost, output_cost, cache_read_cost, cache_write_cost,
                                          cost_source, price_book_version, input_preview, output_preview,
                                          correlation_state, path_state, event_ts, is_deleted)
                        VALUES (:pid, :traceId, :id, :parentSpanId, :path::ltree, :sessionId, :userId,
                                :pvid, :callSiteId, :traceName, :kind, :name, :isLogicalRoot, :status, :level,
                                :errorType, :errorMessage, :startedAt::timestamptz, :endedAt::timestamptz,
                                :latencyMs, :ttftMs,
                                :providedModelName, :modelId, :inputTokens, :outputTokens, :cacheReadTokens,
                                :cacheWriteTokens, :reasoningTokens, :inputCost::numeric, :outputCost::numeric,
                                :cacheReadCost::numeric, :cacheWriteCost::numeric, :costSource,
                                :priceBookVersion, :inputPreview, :outputPreview, :correlationState, :pathState,
                                :eventTs::timestamptz, :isDeleted)
                        ON CONFLICT (project_id, trace_id, id) DO UPDATE
                           SET parent_span_id      = excluded.parent_span_id,
                               session_id          = excluded.session_id,
                               user_id             = excluded.user_id,
                               project_version_id  = excluded.project_version_id,
                               call_site_id        = excluded.call_site_id,
                               trace_name          = excluded.trace_name,
                               kind                = excluded.kind,
                               name                = excluded.name,
                               is_logical_root     = excluded.is_logical_root,
                               status              = excluded.status,
                               level               = excluded.level,
                               error_type          = excluded.error_type,
                               error_message       = excluded.error_message,
                               started_at          = excluded.started_at,
                               ended_at            = excluded.ended_at,
                               latency_ms          = excluded.latency_ms,
                               ttft_ms             = excluded.ttft_ms,
                               provided_model_name = excluded.provided_model_name,
                               model_id            = excluded.model_id,
                               input_tokens        = excluded.input_tokens,
                               output_tokens       = excluded.output_tokens,
                               cache_read_tokens   = excluded.cache_read_tokens,
                               cache_write_tokens  = excluded.cache_write_tokens,
                               reasoning_tokens    = excluded.reasoning_tokens,
                               input_cost          = excluded.input_cost,
                               output_cost         = excluded.output_cost,
                               cache_read_cost     = excluded.cache_read_cost,
                               cache_write_cost    = excluded.cache_write_cost,
                               cost_source         = excluded.cost_source,
                               price_book_version  = excluded.price_book_version,
                               input_preview       = excluded.input_preview,
                               output_preview      = excluded.output_preview,
                               event_ts            = excluded.event_ts,
                               is_deleted          = excluded.is_deleted
                         WHERE excluded.event_ts >= span.event_ts
                        """;

    private static MapSqlParameterSource params(SpanRow row) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue("pid", row.projectId());
        source.addValue("traceId", row.traceId());
        source.addValue("id", row.id());
        source.addValue("parentSpanId", row.parentSpanId());
        source.addValue("path", row.path());
        source.addValue("sessionId", row.sessionId());
        source.addValue("userId", row.userId());
        source.addValue("pvid", row.projectVersionId());
        source.addValue("callSiteId", row.callSiteId());
        source.addValue("traceName", row.traceName());
        source.addValue("kind", row.kind());
        source.addValue("name", row.name());
        source.addValue("isLogicalRoot", row.isLogicalRoot());
        source.addValue("status", row.status());
        source.addValue("level", row.level());
        source.addValue("errorType", row.errorType());
        source.addValue("errorMessage", row.errorMessage());
        source.addValue("startedAt", row.startedAt());
        source.addValue("endedAt", row.endedAt());
        source.addValue("latencyMs", row.latencyMs());
        source.addValue("ttftMs", row.ttftMs());
        source.addValue("providedModelName", row.providedModelName());
        source.addValue("modelId", row.modelId());
        source.addValue("inputTokens", row.inputTokens());
        source.addValue("outputTokens", row.outputTokens());
        source.addValue("cacheReadTokens", row.cacheReadTokens());
        source.addValue("cacheWriteTokens", row.cacheWriteTokens());
        source.addValue("reasoningTokens", row.reasoningTokens());
        source.addValue("inputCost", row.inputCost());
        source.addValue("outputCost", row.outputCost());
        source.addValue("cacheReadCost", row.cacheReadCost());
        source.addValue("cacheWriteCost", row.cacheWriteCost());
        source.addValue("costSource", row.costSource());
        source.addValue("priceBookVersion", row.priceBookVersion());
        source.addValue("inputPreview", row.inputPreview());
        source.addValue("outputPreview", row.outputPreview());
        source.addValue("correlationState", row.correlationState());
        source.addValue("pathState", row.pathState());
        source.addValue("eventTs", row.eventTs());
        source.addValue("isDeleted", row.isDeleted());
        return source;
    }

    /**
     * One JDBC batch for a whole batch of spans: the same statement, {@code event_ts} guard, SET list and
     * re-arm contract as {@link #upsert}, applied per row. This is the production path; {@link #upsert}
     * is the single-row form of it.
     */
    public void upsertAll(List<SpanRow> rows) {
        if (rows.isEmpty()) return;
        int[] applied = named.batchUpdate(
                UPSERT_SQL, rows.stream().map(SpanRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    /**
     * Last-write-wins upsert against the natural key (§6.2).
     *
     * <p><b>The SET list is every producer-sourced column, including {@code parent_span_id}.</b> It
     * deliberately excludes {@code path}, {@code correlation_state} and {@code path_state}, everything the
     * platform derived rather than received, and {@code created_at}, and it cannot touch {@code depth},
     * {@code total_tokens} or {@code total_cost}, which are generated. A newer version replaces what the
     * producer said; it never discards what we resolved, which would send an already-resolved span back
     * into the fixpoint's work queue on every replay.
     *
     * <p><b>The guard is {@code >=}, not {@code >}.</b> Second-granularity SDK clocks make equal timestamps
     * common, so ties go to the latest arrival; a replay rewriting identical content is then a no-op in
     * effect rather than a rejected write. A strictly-greater guard would drop the completed version of
     * every span whose partial shared its timestamp.
     *
     * <p><b>Backward clock steps on the producer can still discard a final version.</b> That is an accepted
     * bounded risk, and the span-lateness histogram measures it as a negative-lateness tail rather than
     * leaving it invisible.
     *
     * <p>The caller must re-arm the trace timer on both paths, insert and conflict, because a version
     * replacement changes token counts and so must un-settle the trace exactly like a new span. And the
     * write must share its transaction with that re-arm (§6.1).
     *
     * @return the number of rows written: 0 when the guard rejected an older version.
     */
    public int upsert(SpanRow row) {
        return jdbc.sql(UPSERT_SQL).paramSource(params(row)).update();
    }

    // ----- resolver work queues (substrate-model.md §6.3, §6.4) ------------------------------------

    /**
     * A span id rendered as one {@code ltree} label.
     *
     * <p>OTel span ids are hex, so for every span this table is designed around it is the identity
     * function. It exists because an ltree label accepts only {@code [A-Za-z0-9_]}, and a single
     * non-conforming producer id would otherwise raise on every pass of the fixpoint, forever, a
     * permanently wedged resolver rather than one bad row. Deterministic, so a subtree prefix built the
     * same way still matches (§9).
     */
    private static final String SPAN_ID_LABEL = "text2ltree(regexp_replace(s.id, '[^A-Za-z0-9_]', '_', 'g'))";

    /**
     * Give the batch's producer-declared roots the ancestry they already have: their own id, one level
     * deep. <b>Root is {@code parent_span_id IS NULL}</b>, the producer's statement, and never "the
     * parent has not arrived", which is what a null {@code path} means instead.
     *
     * @return the number of spans resolved.
     */
    public int resolveRootPaths(int limit) {
        return jdbc.sql("WITH due AS ("
                        + "  SELECT project_id, trace_id, id FROM span"
                        + "   WHERE path IS NULL AND path_state = 'pending' AND parent_span_id IS NULL"
                        + "   LIMIT :limit)"
                        + " UPDATE span s SET path = " + SPAN_ID_LABEL + ", path_state = 'resolved'"
                        + "  FROM due"
                        + " WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id")
                .param("limit", limit)
                .update();
    }

    /**
     * One pass of the ancestry fixpoint: extend a resolved parent's path with the child's own id.
     *
     * <p>Each pass resolves exactly one level, and a batch exporter flushes a span when it ends, so
     * chains arrive deepest-first and a chain of depth <i>n</i> converges in at most <i>n</i> passes. The
     * partial index {@code ix_span_unresolved_path} is what keeps each pass a small indexed scan instead of
     * a table scan over everything already resolved.
     *
     * <p><b>Selection is join-driven: a claimed row is always resolvable.</b> The parent-is-resolved
     * {@code EXISTS} lives in the {@code due} CTE, not only in the outer join, so the {@code LIMIT} spends
     * its budget on rows that will actually be updated. With the condition in the outer join alone, an
     * unordered {@code LIMIT} kept re-claiming the same first entries of the partial index, all of them
     * children whose parent sat further down it. The {@code UPDATE} matched none of them, and rows
     * deeper in the index were never reached. That is permanent head-of-line blocking, not a slow pass: the
     * pending count freezes while the resolver keeps ticking.
     *
     * @return the number of spans resolved this pass; zero means the fixpoint has converged.
     */
    public int resolveChildPaths(int limit) {
        return jdbc.sql("WITH due AS ("
                        + "  SELECT s.project_id, s.trace_id, s.id FROM span s"
                        + "   WHERE s.path IS NULL AND s.path_state = 'pending' AND s.parent_span_id IS NOT NULL"
                        + "     AND EXISTS (SELECT 1 FROM span p"
                        + "                  WHERE p.project_id = s.project_id AND p.trace_id = s.trace_id"
                        + "                    AND p.id = s.parent_span_id AND p.path IS NOT NULL)"
                        + "   LIMIT :limit)"
                        + " UPDATE span s SET path = p.path || " + SPAN_ID_LABEL + ", path_state = 'resolved'"
                        + "  FROM due, span p"
                        + " WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id"
                        + "   AND p.project_id = s.project_id AND p.trace_id = s.trace_id"
                        + "   AND p.id = s.parent_span_id AND p.path IS NOT NULL")
                .param("limit", limit)
                .update();
    }

    /**
     * Retire spans whose ancestry can never resolve: the trace has settled and there is no non-orphan
     * parent row for them to hang off, either the producer never shipped the parent, or the parent is
     * itself an orphan.
     *
     * <p><b>This is what keeps the fixpoint drainable</b> (implementation plan §2.6). Without a terminal
     * state, every permanently-unresolvable span stays in {@code ix_span_unresolved_path} forever; once the
     * permanent residents outnumber the pass limit, genuine work is never selected again. That is a
     * correctness failure, not a slow query.
     *
     * <p>A parent that exists but is still {@code pending} deliberately blocks the marking: the fixpoint
     * has not finished with it yet, and orphaning its children early would strand a subtree that was about
     * to resolve.
     *
     * @return the number of spans marked orphan.
     */
    public int markOrphanPaths(int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT s.project_id, s.trace_id, s.id
                              FROM span s
                              JOIN trace t ON t.project_id = s.project_id AND t.id = s.trace_id
                             WHERE s.path IS NULL AND s.path_state = 'pending'
                               AND s.parent_span_id IS NOT NULL AND t.is_settled
                               AND NOT EXISTS (
                                   SELECT 1 FROM span p
                                    WHERE p.project_id = s.project_id AND p.trace_id = s.trace_id
                                      AND p.id = s.parent_span_id AND p.path_state <> 'orphan')
                             LIMIT :limit
                        )
                        UPDATE span s SET path_state = 'orphan'
                          FROM due
                         WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id
                        """).param("limit", limit).update();
    }

    /**
     * Copy the correlation handles down from the trace row onto spans that arrived without them (§6.3).
     *
     * <p>This is the FALLBACK path, not the primary one. Our SDK puts session, user and trace name in
     * OpenTelemetry Baggage so every span self-describes and no server-side join is needed; this exists for
     * third-party OTel producers that do not propagate, and the partial index {@code ix_span_uncorrelated}
     * is what keeps it cheap.
     *
     * @return the number of spans correlated.
     */
    public int backfillCorrelation(int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT project_id, trace_id, id
                              FROM span
                             WHERE session_id IS NULL AND correlation_state = 'pending'
                             LIMIT :limit
                        )
                        UPDATE span s
                           SET session_id        = t.session_id,
                               user_id           = COALESCE(s.user_id, t.user_id),
                               trace_name        = COALESCE(s.trace_name, t.name),
                               correlation_state = 'done'
                          FROM due, trace t
                         WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id
                           AND t.project_id = s.project_id AND t.id = s.trace_id
                           AND t.session_id IS NOT NULL
                        """).param("limit", limit).update();
    }

    /**
     * Retire spans there will never be a session to copy: the trace has settled carrying a null
     * {@code session_id}, which is what anonymous traffic looks like and is a legitimate permanent state.
     *
     * <p>The counterpart of {@link #markOrphanPaths}, and load-bearing for the same reason: anonymous
     * traffic is a large, permanent population, and without a terminal state it would sit in
     * {@code ix_span_uncorrelated} being re-read on every tick until it crowded out every span that
     * actually had a session waiting for it.
     *
     * @return the number of spans marked as having no correlation to inherit.
     */
    public int markCorrelationNone(int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT project_id, trace_id, id
                              FROM span
                             WHERE session_id IS NULL AND correlation_state = 'pending'
                             LIMIT :limit
                        )
                        UPDATE span s SET correlation_state = 'none'
                          FROM due, trace t
                         WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id
                           AND t.project_id = s.project_id AND t.id = s.trace_id
                           AND t.session_id IS NULL AND t.is_settled
                        """).param("limit", limit).update();
    }

    /** Spans still awaiting ancestry, the depth of {@code ix_span_unresolved_path}, for the heartbeat. */
    public int pendingPathCount() {
        return jdbc.sql("SELECT count(*) FROM span WHERE path IS NULL AND path_state = 'pending'")
                .query(Integer.class)
                .single();
    }

    /** Spans still awaiting correlation, the depth of {@code ix_span_uncorrelated}, for the heartbeat. */
    public int pendingCorrelationCount() {
        return jdbc.sql("SELECT count(*) FROM span WHERE session_id IS NULL AND correlation_state = 'pending'")
                .query(Integer.class)
                .single();
    }

    /**
     * A trace's spans, in ancestry-then-time order. Reads the primary-key prefix, which is also the
     * physical clustering, so this is the read the rollup recompute and the trace view both make.
     *
     * <p>Ordered by {@code (started_at, id)} rather than by arrival: {@code created_at} records when we
     * received a span, and a batch exporter flushes on END, so arrival order is close to reverse causal
     * order. Event time is the order the work actually happened in.
     */
    public List<SpanRow> listByTrace(String projectId, String traceId) {
        return jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND trace_id = :tid"
                        + " ORDER BY started_at ASC, id ASC")
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The same read, bounded, for a caller that renders at most N spans and must not pay for a trace that
     * has far more.
     *
     * <p>The unbounded overload above is right for the rollup recompute, which genuinely needs every span.
     * It is wrong for a display read: a long agent loop emits a span per tool call, so a trace can reach
     * six figures, and materializing all of them to serialize the first two hundred is heap spent to be
     * thrown away. Ask for {@code cap + 1} and the extra row still answers "was there more?" without a
     * second query, exactly as the keyset pages do.
     */
    public List<SpanRow> listByTrace(String projectId, String traceId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND trace_id = :tid"
                        + " ORDER BY started_at ASC, id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The spans of every trace in {@code traceIds}, in one ancestry-then-time-ordered read, the batched
     * sibling of {@link #listByTrace(String, String, int)} for a session's traces rather than one trace's.
     * Filters {@code trace_id = ANY(:ids)}, which reads the same {@code (project_id, trace_id, id)}
     * primary-key clustering the single-trace read does, just for a set instead of one id, no new index.
     *
     * <p>Bounded the same way: ask for {@code cap + 1} and the extra row answers "was there more?" without a
     * second query.
     */
    public List<SpanRow> listByTraceIds(String projectId, Collection<String> traceIds, int limit) {
        if (traceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND trace_id = ANY(:ids)"
                        + " ORDER BY started_at ASC, id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("ids", traceIds.toArray(String[]::new))
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The spans named by {@code keys}, in one query, the hydration read behind a page of spans that some
     * other index chose (a keyword search page, a kNN ranking). A row per key would be fifty round trips for
     * a fifty-row page.
     *
     * <p><b>The result is unordered and may be shorter than {@code keys}.</b> SQL has no inherent order over
     * an id set, and the ordering that matters here, recency for a keyset page, cosine distance for a
     * ranking, is the caller's, so the caller re-applies it. Short is a race, not an error: a span can be
     * removed by the retention sweep between the index read and this one, and dropping it is right. Both
     * facts are the same ones {@code QueryRepository.searchByIds} lives with.
     */
    public List<SpanRow> listByKeys(String projectId, List<SpanKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        var spec = jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND "
                        + SpanKey.tupleIn("trace_id", "id", keys.size()))
                .param("pid", projectId);
        return SpanKey.bind(spec, keys).query((rs, n) -> map(rs)).list();
    }

    /** One span by its full identity. There is no lookup by bare id: the trace is part of the key. */
    public Optional<SpanRow> findById(String projectId, String traceId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM span" + " WHERE project_id = :pid AND trace_id = :tid AND id = :id")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * Everything a sub-agent did, at any depth, in one indexed query, {@code path <@ :prefix}, served by
     * the gist index (§9).
     *
     * <p>Complete only once the trace's ancestry has resolved; on a settled trace it always is.
     */
    public List<SpanRow> listSubtree(String projectId, String pathPrefix) {
        return jdbc.sql("SELECT " + COLS + " FROM span"
                        + " WHERE project_id = :pid AND path <@ :prefix::ltree"
                        + " ORDER BY started_at ASC, id ASC")
                .param("pid", projectId)
                .param("prefix", pathPrefix)
                .query((rs, n) -> map(rs))
                .list();
    }

    // ---- entry-shaped reads (substrate-as-source) ------------------------------------------------

    /**
     * One span in the shape the grading lane's source adapter wants: identity and correlation columns off
     * {@code span}, plus the three payload fields off {@code span_payload}. A projection, not a row type:
     * {@link SpanRow} deliberately carries no payload, because every list surface reads spans without ever
     * touching the payload table, and this is the one read that needs both.
     *
     * <p>The join is a LEFT join: a span whose payload has aged out (spec §10) still exists, still grades
     * as a step in the trace, and simply has no text. An INNER join would silently shorten a
     * retention-trimmed trace instead of showing it with empty entries.
     */
    public record SpanEntry(
            String projectId,
            String traceId,
            String id,
            @Nullable String parentSpanId,
            String kind,
            @Nullable String name,
            @Nullable String callSiteId,
            @Nullable String providedModelName,
            String startedAt,
            @Nullable String endedAt,
            @Nullable String input,
            @Nullable String output,
            @Nullable String attributes) {

        /** The producer handle {@code "<trace_id>:<span_id>"}, the id a caller hands back to a point read. */
        public String handle() {
            return traceId + ':' + id;
        }
    }

    private static final String ENTRY_COLS = "s.project_id, s.trace_id, s.id, s.parent_span_id, s.kind, s.name, "
            + "s.call_site_id, s.provided_model_name, s.started_at, s.ended_at, "
            + "p.input, p.output, p.attributes";

    private static final String ENTRY_FROM = " FROM span s LEFT JOIN span_payload p"
            + " ON p.project_id = s.project_id AND p.trace_id = s.trace_id AND p.span_id = s.id";

    /**
     * Project-scoped, time-bounded, paginated entry read backing the substrate-as-source adapter
     * ({@code SubstrateSource} for the {@code sdk} provider). Newest first by {@code started_at} so a
     * preview / live-dataset run sees the most recent ingested telemetry; bounds are inclusive ISO-8601
     * strings, {@code null} imposes no bound. Paging is by {@code limit}/{@code offset}; the adapter walks
     * pages until a short page signals exhaustion.
     */
    public List<SpanEntry> listEntriesByProject(
            String projectId, @Nullable String fromIso, @Nullable String toIso, int limit, int offset) {
        String sql = "SELECT " + ENTRY_COLS + ENTRY_FROM + " WHERE s.project_id = :pid"
                + (fromIso != null ? " AND s.started_at >= :from::timestamptz" : "")
                + (toIso != null ? " AND s.started_at <= :to::timestamptz" : "")
                + " ORDER BY s.started_at DESC, s.trace_id DESC, s.id DESC"
                + " LIMIT :limit OFFSET :offset";
        var spec = jdbc.sql(sql).param("pid", projectId).param("limit", limit).param("offset", offset);
        if (fromIso != null) {
            spec = spec.param("from", fromIso);
        }
        if (toIso != null) {
            spec = spec.param("to", toIso);
        }
        return spec.query((rs, n) -> mapEntry(rs)).list();
    }

    /** A trace's entries in event-time order, the primary-key-prefix read, with payloads joined on. */
    public List<SpanEntry> listEntriesByTrace(String projectId, String traceId) {
        return jdbc.sql("SELECT " + ENTRY_COLS + ENTRY_FROM
                        + " WHERE s.project_id = :pid AND s.trace_id = :tid"
                        + " ORDER BY s.started_at ASC, s.id ASC")
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> mapEntry(rs))
                .list();
    }

    /** One entry by its full identity. As everywhere in v2, the trace is part of the key. */
    public Optional<SpanEntry> findEntry(String projectId, String traceId, String id) {
        return jdbc.sql("SELECT " + ENTRY_COLS + ENTRY_FROM
                        + " WHERE s.project_id = :pid AND s.trace_id = :tid AND s.id = :id")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("id", id)
                .query((rs, n) -> mapEntry(rs))
                .optional();
    }

    /**
     * Newest entries stamped with a given call site (served by {@code ix_span_call_site}). The
     * substrate-direct trace-selection read that lets grader synthesis ground by {@code call_site_id}
     * (tag-as-the-model) instead of re-deriving the call site via source mappings. Newest first by
     * {@code started_at}; the caller groups the rows into distinct traces.
     *
     * @param notBefore when non-null, only rows with {@code started_at >= notBefore} are eligible, bounds
     *     grounding to recent activity. {@code null} = no age bound.
     */
    public List<SpanEntry> recentEntriesByCallSite(
            String projectId, String callSiteId, int limit, @Nullable Instant notBefore) {
        String sql = "SELECT " + ENTRY_COLS + ENTRY_FROM
                + " WHERE s.project_id = :pid AND s.call_site_id = :cs"
                + (notBefore != null ? " AND s.started_at >= :notBefore::timestamptz" : "")
                + " ORDER BY s.started_at DESC, s.trace_id DESC, s.id DESC"
                + " LIMIT :limit";
        var spec = jdbc.sql(sql).param("pid", projectId).param("cs", callSiteId).param("limit", limit);
        if (notBefore != null) {
            spec = spec.param("notBefore", notBefore.toString());
        }
        return spec.query((rs, n) -> mapEntry(rs)).list();
    }

    private static SpanEntry mapEntry(ResultSet rs) throws SQLException {
        return new SpanEntry(
                rs.getString("project_id"),
                rs.getString("trace_id"),
                rs.getString("id"),
                rs.getString("parent_span_id"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getString("call_site_id"),
                rs.getString("provided_model_name"),
                requireIso(rs, "started_at"),
                Timestamps.iso(rs, "ended_at"),
                rs.getString("input"),
                rs.getString("output"),
                rs.getString("attributes"));
    }

    private static SpanRow map(ResultSet rs) throws SQLException {
        return new SpanRow(
                rs.getString("project_id"),
                rs.getString("trace_id"),
                rs.getString("id"),
                rs.getString("parent_span_id"),
                rs.getString("path"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("project_version_id"),
                rs.getString("call_site_id"),
                rs.getString("trace_name"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getBoolean("is_logical_root"),
                rs.getString("status"),
                rs.getString("level"),
                rs.getString("error_type"),
                rs.getString("error_message"),
                requireIso(rs, "started_at"),
                Timestamps.iso(rs, "ended_at"),
                longOrNull(rs, "latency_ms"),
                longOrNull(rs, "ttft_ms"),
                rs.getString("provided_model_name"),
                rs.getString("model_id"),
                longOrNull(rs, "input_tokens"),
                longOrNull(rs, "output_tokens"),
                longOrNull(rs, "cache_read_tokens"),
                longOrNull(rs, "cache_write_tokens"),
                longOrNull(rs, "reasoning_tokens"),
                numericOrNull(rs, "input_cost"),
                numericOrNull(rs, "output_cost"),
                numericOrNull(rs, "cache_read_cost"),
                numericOrNull(rs, "cache_write_cost"),
                rs.getString("cost_source"),
                rs.getString("price_book_version"),
                rs.getString("input_preview"),
                rs.getString("output_preview"),
                rs.getString("correlation_state"),
                rs.getString("path_state"),
                requireIso(rs, "event_ts"),
                rs.getBoolean("is_deleted"),
                intOrNull(rs, "depth"),
                longOrNull(rs, "total_tokens"),
                numericOrNull(rs, "total_cost"),
                Timestamps.iso(rs, "created_at"));
    }
}
