// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static ai.tessary.storage.SessionRepository.requireIso;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trace table and the rollup protocol that maintains it (substrate-model.md §5.1, §7). Writes are identity-only
 * inserts, idempotent min/max timer and correlation updates, and a worker recompute that replaces every sum from the
 * spans rather than applying deltas, so any bug self-heals. {@link #reap} recovers traces a dead worker left armed for
 * nobody.
 */
@Repository
public class TraceV2Repository {

    private static final String COLS = "project_id, id, session_id, parent_trace_id, thread_id, name, user_id, "
            + "project_version_id, status, started_at, ended_at, latency_ms, span_count, "
            + "error_count, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, "
            + "reasoning_tokens, total_tokens, input_cost, output_cost, total_cost, unpriced_spans, "
            + "input_preview, output_preview, call_site_id, rollup_due_at, rolled_up_at, rolled_up_through, "
            + "is_settled, has_root_span, event_ts, is_deleted";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    private final TraceFilters filters;

    public TraceV2Repository(JdbcClient jdbc, NamedParameterJdbcTemplate named, TraceFilters filters) {
        this.jdbc = jdbc;
        this.named = named;
        this.filters = filters;
    }

    private static final String GET_OR_CREATE_SQL = """
                        INSERT INTO trace (project_id, id, session_id, parent_trace_id, thread_id, name, user_id,
                                        project_version_id, status, started_at, event_ts,
                                        is_deleted)
                        VALUES (:pid, :id, :sessionId, :parentTraceId, :threadId, :name, :userId, :pvid,
                                :status, :startedAt::timestamptz, :eventTs::timestamptz, :isDeleted)
                        ON CONFLICT (project_id, id) DO NOTHING
                        """;

    private static MapSqlParameterSource params(TraceV2Row row) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue("pid", row.projectId());
        source.addValue("id", row.id());
        source.addValue("sessionId", row.sessionId());
        source.addValue("parentTraceId", row.parentTraceId());
        source.addValue("threadId", row.threadId());
        source.addValue("name", row.name());
        source.addValue("userId", row.userId());
        source.addValue("pvid", row.projectVersionId());
        source.addValue("status", row.status());
        source.addValue("startedAt", row.startedAt());
        source.addValue("eventTs", row.eventTs());
        source.addValue("isDeleted", row.isDeleted());
        return source;
    }

    public void getOrCreateAll(List<TraceV2Row> rows) {
        int[] applied = named.batchUpdate(
                GET_OR_CREATE_SQL, rows.stream().map(TraceV2Repository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    /** {@code maxEndedAt} is null when no span in the batch had ended. */
    public record TimerUpdate(
            String traceId, String minStartedAt, @Nullable String maxEndedAt, boolean hasRoot) {}

    /**
     * Fold a batch into its traces' timers and re-arm the rollup deadline (§7.1). The deadline only ever moves earlier,
     * so a long-running turn still refreshes. The {@code FOR UPDATE} pre-pass locks in {@code (project_id, id)} order
     * so concurrent batches cannot deadlock, and must share the transaction that wrote the spans or a settling rollup
     * could miss a span.
     */
    public int applyBatchTimers(String projectId, Collection<TimerUpdate> updates) {
        List<TimerUpdate> sorted = updates.stream()
                .sorted(Comparator.comparing(TimerUpdate::traceId))
                .toList();

        jdbc.sql("SELECT 1 FROM trace WHERE project_id = :pid AND id IN (:ids)" + " ORDER BY project_id, id FOR UPDATE")
                .param("pid", projectId)
                .param("ids", sorted.stream().map(TimerUpdate::traceId).toList())
                .query()
                .listOfRows();

        StringBuilder values = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            values.append(i == 0 ? "" : ", ")
                    .append("(:tid")
                    .append(i)
                    .append(", :st")
                    .append(i)
                    .append("::timestamptz, :en")
                    .append(i)
                    .append("::timestamptz, :rt")
                    .append(i)
                    .append("::boolean)");
        }
        var spec = jdbc.sql("""
                        UPDATE trace t SET
                            started_at    = LEAST(t.started_at, v.min_started_at),
                            ended_at      = GREATEST(t.ended_at, v.max_ended_at),
                            has_root_span = t.has_root_span OR v.has_root,
                            rollup_due_at = LEAST(
                                                COALESCE(t.rollup_due_at, 'infinity'::timestamptz),
                                                now() + CASE WHEN v.has_root OR t.has_root_span
                                                             THEN interval '2 seconds'
                                                             ELSE interval '10 seconds' END),
                            is_settled    = false
                        FROM (VALUES """ + values + """
                        ) AS v (trace_id, min_started_at, max_ended_at, has_root)
                        WHERE t.project_id = :pid AND t.id = v.trace_id
                        """).param("pid", projectId);
        for (int i = 0; i < sorted.size(); i++) {
            TimerUpdate u = sorted.get(i);
            spec = spec.param("tid" + i, u.traceId())
                    .param("st" + i, u.minStartedAt())
                    .param("en" + i, u.maxEndedAt())
                    .param("rt" + i, u.hasRoot());
        }
        return spec.update();
    }

    /** A child span never names its trace. */
    public record CorrelationFill(
            String traceId,
            @Nullable String name,
            @Nullable String userId,
            @Nullable String sessionId,
            @Nullable String threadId) {}

    /**
     * Fill {@code name}, {@code user_id}, {@code session_id} and {@code thread_id} where still null: a batch exporter
     * ships the root last. A set value is never replaced, so a trace cannot move between sessions. Runs under the locks
     * {@link #applyBatchTimers} already holds.
     */
    public List<String> fillCorrelation(String projectId, Collection<CorrelationFill> fills) {
        List<CorrelationFill> sorted = fills.stream()
                .filter(f -> f.name() != null || f.userId() != null || f.sessionId() != null || f.threadId() != null)
                .sorted(Comparator.comparing(CorrelationFill::traceId))
                .toList();
        if (sorted.isEmpty()) return List.of();

        StringBuilder values = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            values.append(i == 0 ? "" : ", ")
                    .append("(:tid")
                    .append(i)
                    .append(", :nm")
                    .append(i)
                    .append("::text, :uid")
                    .append(i)
                    .append("::text, :sid")
                    .append(i)
                    .append("::text, :thr")
                    .append(i)
                    .append("::text)");
        }
        // `o` is the row before this statement writes it, so RETURNING can tell a session filled here from one already
        // set.
        var spec = jdbc.sql("""
                        UPDATE trace t SET
                            name       = COALESCE(t.name, v.name),
                            user_id    = COALESCE(t.user_id, v.user_id),
                            session_id = COALESCE(t.session_id, v.session_id),
                            thread_id  = COALESCE(t.thread_id, v.thread_id)
                        FROM (VALUES """ + values + """
                        ) AS v (trace_id, name, user_id, session_id, thread_id)
                        JOIN trace o ON o.project_id = :pid AND o.id = v.trace_id
                        WHERE t.project_id = :pid AND t.id = v.trace_id
                          AND ((t.name IS NULL AND v.name IS NOT NULL)
                               OR (t.user_id IS NULL AND v.user_id IS NOT NULL)
                               OR (t.session_id IS NULL AND v.session_id IS NOT NULL)
                               OR (t.thread_id IS NULL AND v.thread_id IS NOT NULL))
                        RETURNING t.id, (o.session_id IS NULL AND v.session_id IS NOT NULL) AS session_filled
                        """).param("pid", projectId);
        for (int i = 0; i < sorted.size(); i++) {
            CorrelationFill f = sorted.get(i);
            spec = spec.param("tid" + i, f.traceId())
                    .param("nm" + i, f.name())
                    .param("uid" + i, f.userId())
                    .param("sid" + i, f.sessionId())
                    .param("thr" + i, f.threadId());
        }
        return spec.query((rs, n) -> new Filled(rs.getString("id"), rs.getBoolean("session_filled"))).list().stream()
                .filter(Filled::sessionFilled)
                .map(Filled::traceId)
                .toList();
    }

    private record Filled(String traceId, boolean sessionFilled) {}

    public record Claim(String projectId, String traceId) {}

    /**
     * {@code FOR UPDATE SKIP LOCKED}, so workers need no coordination. Clearing {@code rollup_due_at} is the
     * correctness crux: a span arriving afterwards re-arms it, and {@link #recompute} then declines to settle.
     */
    public List<Claim> claimDue(int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT project_id, id
                              FROM trace
                             WHERE rollup_due_at <= now()
                             ORDER BY rollup_due_at
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED
                        )
                        UPDATE trace SET rollup_due_at = NULL
                          FROM due
                         WHERE trace.project_id = due.project_id AND trace.id = due.id
                        RETURNING trace.project_id, trace.id
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Claim(rs.getString("project_id"), rs.getString("id")))
                .list();
    }

    /** {@code settled} is false when a span arrived mid-rollup, which is expected, not an error. */
    public record Recomputed(boolean settled, int spanCount) {}

    /**
     * Recompute one claimed trace's rollups as a replacement read from its spans (§7.2/§7.3). {@code rollup_due_at} is
     * never written: the trace settles only if nothing re-armed it since the claim. {@code unpriced_spans} counts spans
     * with more than zero tokens, since producers emit placeholder zero-token spans. Previews and call site are copied
     * down from the root (else the earliest tagged span) only once the root has landed.
     */
    public Optional<Recomputed> recompute(String projectId, String traceId) {
        return jdbc.sql("""
                        WITH agg AS (
                            SELECT count(*)                                        AS span_count,
                                   count(*) FILTER (WHERE status = 'error')        AS error_count,
                                   sum(input_tokens)                               AS input_tokens,
                                   sum(output_tokens)                              AS output_tokens,
                                   sum(cache_read_tokens)                          AS cache_read_tokens,
                                   sum(cache_write_tokens)                         AS cache_write_tokens,
                                   sum(reasoning_tokens)                           AS reasoning_tokens,
                                   sum(total_tokens)                               AS total_tokens,
                                   sum(input_cost)                                 AS input_cost,
                                   sum(output_cost)                                AS output_cost,
                                   sum(total_cost)                                 AS total_cost,
                                   count(*) FILTER (WHERE cost_source = 'unpriced'
                                                      AND total_tokens > 0)         AS unpriced_spans,
                                   max(event_ts)                                   AS through
                              FROM span
                             WHERE project_id = :pid
                               AND trace_id   = :tid
                               AND NOT is_deleted
                        ), root AS (
                            SELECT input_preview, output_preview, call_site_id
                              FROM span
                             WHERE project_id     = :pid
                               AND trace_id       = :tid
                               AND parent_span_id IS NULL
                               AND NOT is_deleted
                             ORDER BY started_at, id
                             LIMIT 1
                        ), tagged AS (
                            SELECT call_site_id
                              FROM span
                             WHERE project_id   = :pid
                               AND trace_id     = :tid
                               AND call_site_id IS NOT NULL
                               AND NOT is_deleted
                             ORDER BY started_at, id
                             LIMIT 1
                        )
                        UPDATE trace t
                           SET span_count         = agg.span_count,
                               error_count        = agg.error_count,
                               input_tokens       = agg.input_tokens,
                               output_tokens      = agg.output_tokens,
                               cache_read_tokens  = agg.cache_read_tokens,
                               cache_write_tokens = agg.cache_write_tokens,
                               reasoning_tokens   = agg.reasoning_tokens,
                               total_tokens       = agg.total_tokens,
                               input_cost         = agg.input_cost,
                               output_cost        = agg.output_cost,
                               total_cost         = agg.total_cost,
                               unpriced_spans     = agg.unpriced_spans,
                               input_preview      = CASE WHEN t.has_root_span THEN root.input_preview
                                                         ELSE t.input_preview END,
                               output_preview     = CASE WHEN t.has_root_span THEN root.output_preview
                                                         ELSE t.output_preview END,
                               call_site_id       = CASE WHEN t.has_root_span
                                                         THEN COALESCE(root.call_site_id, tagged.call_site_id)
                                                         ELSE t.call_site_id END,
                               rolled_up_at       = now(),
                               rolled_up_through  = agg.through,
                               is_settled         = (t.rollup_due_at IS NULL)
                          FROM agg LEFT JOIN root ON true LEFT JOIN tagged ON true
                         WHERE t.project_id = :pid AND t.id = :tid
                        RETURNING t.is_settled, t.span_count
                        """)
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> new Recomputed(rs.getBoolean("is_settled"), rs.getInt("span_count")))
                .optional();
    }

    /**
     * Re-arm traces a dead worker left with {@code is_settled = false}, no deadline and no recent rollup. Safe at any
     * frequency, since every rollup is a replacement.
     */
    public int reap(int graceSeconds) {
        return jdbc.sql("""
                        UPDATE trace
                           SET rollup_due_at = now()
                         WHERE is_settled = false
                           AND rollup_due_at IS NULL
                           AND (rolled_up_at IS NULL
                                OR rolled_up_at < now() - make_interval(secs => :grace))
                        """).param("grace", graceSeconds).update();
    }

    /**
     * {@code overdueMs} is the age of the oldest DUE deadline, which the staleness alarm watches; depth alone is just
     * traffic.
     */
    public record RollupQueue(int depth, long overdueMs) {}

    public RollupQueue queueStats() {
        return jdbc.sql("""
                        SELECT count(*) AS depth,
                               COALESCE(
                                   max(extract(epoch FROM (now() - rollup_due_at)) * 1000)
                                       FILTER (WHERE rollup_due_at <= now()),
                                   0)::bigint AS overdue_ms
                          FROM trace
                         WHERE rollup_due_at IS NOT NULL
                        """)
                .query((rs, n) -> new RollupQueue(rs.getInt("depth"), rs.getLong("overdue_ms")))
                .single();
    }

    /** Traces that never rolled up are absent, not null: a span landing in one is first, not late. */
    public Map<String, String> rolledUpThrough(String projectId, Collection<String> traceIds) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("SELECT id, rolled_up_through FROM trace"
                        + " WHERE project_id = :pid AND id IN (:ids) AND rolled_up_through IS NOT NULL")
                .param("pid", projectId)
                .param("ids", List.copyOf(traceIds))
                .query((rs, n) -> {
                    out.put(rs.getString("id"), Timestamps.iso(rs, "rolled_up_through"));
                    return rs.getString("id");
                })
                .list();
        return out;
    }

    /**
     * Optional filters that compose with AND. {@code model}, {@code kind}, {@code callSite} and {@code hasCallSite} are
     * {@code EXISTS} semi-joins over spans; {@code detectedBy} is a classifier id or {@code any}. {@link TraceFilters}
     * writes the SQL.
     */
    public record TraceQuery(
            @Nullable String model,
            @Nullable String kind,
            @Nullable String callSite,
            @Nullable Boolean hasCallSite,
            @Nullable String from,
            @Nullable String to,
            @Nullable String status,
            @Nullable String q,
            @Nullable String detectedBy) {

        public static final TraceQuery NONE = new TraceQuery(null, null, null, null, null, null, null, null, null);
    }

    /**
     * Every number was computed by {@link #recompute}, not by the statement serving this row. A null total with
     * {@code isSettled = false} means not rolled up yet; settled with a null total means no span reported usage.
     */
    public record Summary(
            String id,
            @Nullable String name,
            String startedAt,
            @Nullable String endedAt,
            @Nullable Long latencyMs,
            @Nullable String sessionId,
            @Nullable String userId,
            @Nullable String threadId,
            @Nullable String callSiteId,
            @Nullable Integer spanCount,
            @Nullable Integer errorCount,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            @Nullable Long totalTokens,
            @Nullable BigDecimal inputCost,
            @Nullable BigDecimal outputCost,
            @Nullable BigDecimal totalCost,
            @Nullable Integer unpricedSpans,
            boolean isSettled,
            @Nullable String inputPreview,
            @Nullable String outputPreview) {}

    public static final class Sort {
        public static final String TOKENS = "tokens";
        public static final String COST = "cost";
        public static final String LATENCY = "latency";

        private Sort() {}
    }

    /**
     * No {@code COALESCE}: an unrolled trace has no total, and zero would sort every in-flight turn to the cheapest
     * end.
     */
    private static @Nullable String sortColumn(@Nullable String sort) {
        if (sort == null) return null;
        return switch (sort.toLowerCase(java.util.Locale.ROOT)) {
            case Sort.TOKENS -> "t.total_tokens";
            case Sort.COST -> "t.total_cost";
            case Sort.LATENCY -> "t.latency_ms";
            default -> null;
        };
    }

    /**
     * Keyset pagination on {@code (started_at, id)}, prefixed by the sort column for rollup sorts. Nulls sort LAST, so
     * the predicate has a branch for the null tail; a sentinel like {@code COALESCE(cost, -1)} would make an unpriced
     * trace look priced.
     */
    @Transactional(readOnly = true)
    public List<Summary> list(
            String projectId,
            TraceQuery query,
            @Nullable String sort,
            int limit,
            @Nullable String beforeSort,
            @Nullable String beforeStartedAt,
            @Nullable String beforeId) {
        var params = new HashMap<String, Object>();
        params.put("pid", projectId);
        params.put("limit", limit);

        String sortCol = sortColumn(sort);
        var where = new StringBuilder("WHERE t.project_id = :pid AND NOT t.is_deleted");

        if (beforeStartedAt != null && beforeId != null) {
            String tiebreak = "(t.started_at < :beforeStartedAt::timestamptz"
                    + " OR (t.started_at = :beforeStartedAt::timestamptz AND t.id < :beforeId))";
            if (sortCol == null) {
                where.append(" AND ").append(tiebreak);
            } else if (beforeSort == null) {
                where.append(" AND ").append(sortCol).append(" IS NULL AND ").append(tiebreak);
            } else {
                where.append(" AND ((")
                        .append(sortCol)
                        .append(" IS NOT NULL AND (")
                        .append(sortCol)
                        .append(" < :beforeSort::numeric OR (")
                        .append(sortCol)
                        .append(" = :beforeSort::numeric AND ")
                        .append(tiebreak)
                        .append("))) OR ")
                        .append(sortCol)
                        .append(" IS NULL)");
                params.put("beforeSort", beforeSort);
            }
            params.put("beforeStartedAt", beforeStartedAt);
            params.put("beforeId", beforeId);
        }

        try {
            filters.append(projectId, where, params, query);

            String orderBy = sortCol == null
                    ? " ORDER BY t.started_at DESC, t.id DESC LIMIT :limit"
                    : " ORDER BY " + sortCol + " DESC NULLS LAST, t.started_at DESC, t.id DESC LIMIT :limit";

            String sql = "SELECT t.id, t.name, t.started_at, t.ended_at, t.latency_ms, t.session_id, t.user_id,"
                    + " t.thread_id, t.call_site_id, t.span_count, t.error_count, t.input_tokens, t.output_tokens,"
                    + " t.cache_read_tokens, t.cache_write_tokens, t.reasoning_tokens, t.total_tokens,"
                    + " t.input_cost, t.output_cost, t.total_cost, t.unpriced_spans, t.is_settled,"
                    + " t.input_preview, t.output_preview"
                    + " FROM trace t "
                    + where
                    + orderBy;

            return jdbc.sql(sql).params(params).query((rs, n) -> summary(rs)).list();
        } catch (DataAccessException e) {
            throw TraceFilters.searchFailure(e);
        }
    }

    /** The same {@link Summary} projection the list serves, so detail and list can never disagree. */
    public Optional<Summary> findSummary(String projectId, String id) {
        return jdbc.sql("SELECT t.id, t.name, t.started_at, t.ended_at, t.latency_ms, t.session_id, t.user_id,"
                        + " t.thread_id, t.call_site_id, t.span_count, t.error_count, t.input_tokens,"
                        + " t.output_tokens, t.cache_read_tokens, t.cache_write_tokens, t.reasoning_tokens,"
                        + " t.total_tokens, t.input_cost, t.output_cost, t.total_cost, t.unpriced_spans,"
                        + " t.is_settled, t.input_preview, t.output_preview"
                        + " FROM trace t"
                        + " WHERE t.project_id = :pid AND t.id = :id AND NOT t.is_deleted")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> summary(rs))
                .optional();
    }

    /** Stands in for the session rollup this schema deliberately does not have (§7.5). */
    public List<Summary> listBySession(String projectId, String sessionId, int limit) {
        return jdbc.sql("SELECT t.id, t.name, t.started_at, t.ended_at, t.latency_ms, t.session_id, t.user_id,"
                        + " t.thread_id, t.call_site_id, t.span_count, t.error_count, t.input_tokens,"
                        + " t.output_tokens, t.cache_read_tokens, t.cache_write_tokens, t.reasoning_tokens,"
                        + " t.total_tokens, t.input_cost, t.output_cost, t.total_cost, t.unpriced_spans,"
                        + " t.is_settled, t.input_preview, t.output_preview"
                        + " FROM trace t"
                        + " WHERE t.project_id = :pid AND t.session_id = :sid AND NOT t.is_deleted"
                        + " ORDER BY t.started_at ASC, t.id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("sid", sessionId)
                .param("limit", limit)
                .query((rs, n) -> summary(rs))
                .list();
    }

    @Transactional(readOnly = true)
    public List<String> idsInSessionMatching(String projectId, String sessionId, TraceQuery filter, int limit) {
        var params = new HashMap<String, Object>();
        params.put("pid", projectId);
        params.put("sid", sessionId);
        params.put("limit", limit);
        var where = new StringBuilder("WHERE t.project_id = :pid AND t.session_id = :sid AND NOT t.is_deleted");
        try {
            filters.append(projectId, where, params, filter);
            return jdbc.sql("SELECT t.id FROM trace t " + where + " ORDER BY t.started_at ASC, t.id ASC LIMIT :limit")
                    .params(params)
                    .query(String.class)
                    .list();
        } catch (DataAccessException e) {
            throw TraceFilters.searchFailure(e);
        }
    }

    /**
     * {@code settledEnd} is null while the trace is unsettled, since a span may still arrive, or when none of its spans
     * ended.
     */
    public record SessionTrace(
            String id,
            @Nullable String parentTraceId,
            @Nullable String callSiteId,
            String startedAt,
            boolean settled,
            @Nullable String settledEnd) {}

    private static final String SESSION_TRACE_COLUMNS = "SELECT t.id, t.parent_trace_id, t.call_site_id, t.started_at,"
            + " t.is_settled, CASE WHEN t.is_settled THEN t.ended_at END AS settled_end FROM trace t"
            + " WHERE t.project_id = :pid AND t.session_id = :sid AND NOT t.is_deleted";

    static final String UNENDED_SESSION_TRACES = SESSION_TRACE_COLUMNS
            + " AND (NOT t.is_settled OR t.ended_at IS NULL)"
            + " ORDER BY t.started_at DESC, t.id DESC";

    static final String ENDED_SESSION_TRACES = SESSION_TRACE_COLUMNS
            + " AND t.is_settled AND t.ended_at IS NOT NULL"
            + " ORDER BY t.ended_at DESC, t.started_at DESC, t.id DESC LIMIT :limit";

    static final String ENDED_SESSION_TRACES_AFTER = SESSION_TRACE_COLUMNS
            + " AND t.is_settled AND t.ended_at IS NOT NULL"
            + " AND (t.ended_at, t.started_at, t.id) < (:end::timestamptz, :start::timestamptz, :id)"
            + " ORDER BY t.ended_at DESC, t.started_at DESC, t.id DESC LIMIT :limit";

    /**
     * Served by {@code ix_trace_session_unended}, which holds only such traces, so a long session's ended traces are
     * not read.
     */
    public List<SessionTrace> listUnendedSessionTraces(String projectId, String sessionId) {
        return jdbc.sql(UNENDED_SESSION_TRACES)
                .param("pid", projectId)
                .param("sid", sessionId)
                .query((rs, n) -> sessionTrace(rs))
                .list();
    }

    /**
     * Served by {@code ix_trace_session_ended} in this order, so a page reads only its own rows. Together with
     * {@link #listUnendedSessionTraces} it lists every trace latest end first.
     */
    public List<SessionTrace> listEndedSessionTraces(
            String projectId, String sessionId, @Nullable SessionTrace after, int limit) {
        if (after == null) {
            return jdbc.sql(ENDED_SESSION_TRACES)
                    .param("pid", projectId)
                    .param("sid", sessionId)
                    .param("limit", limit)
                    .query((rs, n) -> sessionTrace(rs))
                    .list();
        }
        return jdbc.sql(ENDED_SESSION_TRACES_AFTER)
                .param("pid", projectId)
                .param("sid", sessionId)
                .param("end", Objects.requireNonNull(after.settledEnd(), "an ended trace"))
                .param("start", after.startedAt())
                .param("id", after.id())
                .param("limit", limit)
                .query((rs, n) -> sessionTrace(rs))
                .list();
    }

    private static SessionTrace sessionTrace(ResultSet rs) throws SQLException {
        return new SessionTrace(
                rs.getString("id"),
                rs.getString("parent_trace_id"),
                rs.getString("call_site_id"),
                requireIso(rs, "started_at"),
                rs.getBoolean("is_settled"),
                Timestamps.iso(rs, "settled_end"));
    }

    /** A conversation is a session and a turn is a top-level trace: the grain the frustration classifier scores at. */
    public List<Summary> listByConversation(String projectId, String conversationId, int limit) {
        return jdbc.sql("SELECT t.id, t.name, t.started_at, t.ended_at, t.latency_ms, t.session_id, t.user_id,"
                        + " t.thread_id, t.call_site_id, t.span_count, t.error_count, t.input_tokens,"
                        + " t.output_tokens, t.cache_read_tokens, t.cache_write_tokens, t.reasoning_tokens,"
                        + " t.total_tokens, t.input_cost, t.output_cost, t.total_cost, t.unpriced_spans,"
                        + " t.is_settled, t.input_preview, t.output_preview"
                        + " FROM trace t"
                        + " WHERE t.project_id = :pid AND t.session_id = :cid"
                        + " AND t.parent_trace_id IS NULL AND NOT t.is_deleted"
                        + " ORDER BY t.started_at ASC, t.id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("cid", conversationId)
                .param("limit", limit)
                .query((rs, n) -> summary(rs))
                .list();
    }

    /**
     * The one read the spec permits to add numbers up, because the addends are stored columns of indexed rows.
     * {@code unsettledTraces} says when the total is only a lower bound.
     */
    public record SessionTotals(
            int traceCount,
            int unsettledTraces,
            @Nullable Long totalTokens,
            @Nullable BigDecimal totalCost,
            @Nullable Long spanCount,
            @Nullable Long errorCount,
            @Nullable Long unpricedSpans) {}

    public SessionTotals sessionTotals(String projectId, String sessionId) {
        return jdbc.sql("""
                        SELECT count(*)                                     AS trace_count,
                               count(*) FILTER (WHERE NOT is_settled)       AS unsettled,
                               sum(total_tokens)                            AS total_tokens,
                               sum(total_cost)                              AS total_cost,
                               sum(span_count)                              AS span_count,
                               sum(error_count)                             AS error_count,
                               sum(unpriced_spans)                          AS unpriced_spans
                          FROM trace
                         WHERE project_id = :pid AND session_id = :sid AND NOT is_deleted
                        """)
                .param("pid", projectId)
                .param("sid", sessionId)
                .query((rs, n) -> new SessionTotals(
                        rs.getInt("trace_count"),
                        rs.getInt("unsettled"),
                        longOrNull(rs, "total_tokens"),
                        rs.getBigDecimal("total_cost"),
                        longOrNull(rs, "span_count"),
                        longOrNull(rs, "error_count"),
                        longOrNull(rs, "unpriced_spans")))
                .single();
    }

    public record SessionTotalsRow(
            String sessionId,
            int traceCount,
            int unsettledTraces,
            @Nullable Long totalTokens,
            @Nullable BigDecimal totalCost,
            @Nullable Long spanCount,
            @Nullable Long errorCount,
            @Nullable Long unpricedSpans,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            @Nullable BigDecimal inputCost,
            @Nullable BigDecimal outputCost) {}

    /** Sessions with no traces yet are absent; the caller fills a zeroed row so the SQL stays a plain GROUP BY. */
    public Map<String, SessionTotalsRow> sessionTotalsForIds(String projectId, Collection<String> sessionIds) {
        Map<String, SessionTotalsRow> out = new HashMap<>();
        jdbc.sql("""
                        SELECT session_id,
                               count(*)                                     AS trace_count,
                               count(*) FILTER (WHERE NOT is_settled)       AS unsettled,
                               sum(total_tokens)                            AS total_tokens,
                               sum(total_cost)                              AS total_cost,
                               sum(span_count)                              AS span_count,
                               sum(error_count)                             AS error_count,
                               sum(unpriced_spans)                          AS unpriced_spans,
                               sum(input_tokens)                            AS input_tokens,
                               sum(output_tokens)                           AS output_tokens,
                               sum(cache_read_tokens)                       AS cache_read_tokens,
                               sum(cache_write_tokens)                      AS cache_write_tokens,
                               sum(reasoning_tokens)                        AS reasoning_tokens,
                               sum(input_cost)                              AS input_cost,
                               sum(output_cost)                             AS output_cost
                          FROM trace
                         WHERE project_id = :pid AND session_id = ANY(:ids) AND NOT is_deleted
                         GROUP BY session_id
                        """)
                .param("pid", projectId)
                .param("ids", sessionIds.toArray(String[]::new))
                .query((rs, n) -> out.put(
                        rs.getString("session_id"),
                        new SessionTotalsRow(
                                rs.getString("session_id"),
                                rs.getInt("trace_count"),
                                rs.getInt("unsettled"),
                                longOrNull(rs, "total_tokens"),
                                rs.getBigDecimal("total_cost"),
                                longOrNull(rs, "span_count"),
                                longOrNull(rs, "error_count"),
                                longOrNull(rs, "unpriced_spans"),
                                longOrNull(rs, "input_tokens"),
                                longOrNull(rs, "output_tokens"),
                                longOrNull(rs, "cache_read_tokens"),
                                longOrNull(rs, "cache_write_tokens"),
                                longOrNull(rs, "reasoning_tokens"),
                                rs.getBigDecimal("input_cost"),
                                rs.getBigDecimal("output_cost"))))
                .list();
        return out;
    }

    public record CallSiteCount(String sessionId, String callSiteId, long n, String lastSeenAt) {}

    /**
     * Untagged traces count toward no call site; the caller picks the highest {@code n} per session as its dominant
     * agent.
     */
    public List<CallSiteCount> callSiteFrequencyForIds(String projectId, Collection<String> sessionIds) {
        return jdbc.sql("""
                        SELECT session_id, call_site_id, count(*) AS n, max(started_at) AS last_seen_at
                          FROM trace
                         WHERE project_id = :pid AND session_id = ANY(:ids)
                           AND NOT is_deleted AND call_site_id IS NOT NULL
                         GROUP BY session_id, call_site_id
                        """)
                .param("pid", projectId)
                .param("ids", sessionIds.toArray(String[]::new))
                .query((rs, n) -> new CallSiteCount(
                        rs.getString("session_id"),
                        rs.getString("call_site_id"),
                        rs.getLong("n"),
                        requireIso(rs, "last_seen_at")))
                .list();
    }

    /**
     * Each session's first trace input preview, paired with {@link #lastOutputPreviewForIds} as the "first ask, latest
     * answer" bracket. {@code DISTINCT ON} rides {@code ix_trace_session}, an index-ordered skip.
     */
    public Map<String, String> firstInputPreviewForIds(String projectId, Collection<String> sessionIds) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (session_id) session_id, input_preview
                          FROM trace
                         WHERE project_id = :pid AND session_id = ANY(:ids) AND NOT is_deleted
                         ORDER BY session_id, started_at ASC
                        """)
                .param("pid", projectId)
                .param("ids", sessionIds.toArray(String[]::new))
                .query((rs, n) -> out.put(rs.getString("session_id"), rs.getString("input_preview")))
                .list();
        return out;
    }

    public Map<String, String> lastOutputPreviewForIds(String projectId, Collection<String> sessionIds) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (session_id) session_id, output_preview
                          FROM trace
                         WHERE project_id = :pid AND session_id = ANY(:ids) AND NOT is_deleted
                         ORDER BY session_id, started_at DESC
                        """)
                .param("pid", projectId)
                .param("ids", sessionIds.toArray(String[]::new))
                .query((rs, n) -> out.put(rs.getString("session_id"), rs.getString("output_preview")))
                .list();
        return out;
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(
                rs.getString("id"),
                rs.getString("name"),
                requireIso(rs, "started_at"),
                Timestamps.iso(rs, "ended_at"),
                longOrNull(rs, "latency_ms"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("thread_id"),
                rs.getString("call_site_id"),
                intOrNull(rs, "span_count"),
                intOrNull(rs, "error_count"),
                longOrNull(rs, "input_tokens"),
                longOrNull(rs, "output_tokens"),
                longOrNull(rs, "cache_read_tokens"),
                longOrNull(rs, "cache_write_tokens"),
                longOrNull(rs, "reasoning_tokens"),
                longOrNull(rs, "total_tokens"),
                rs.getBigDecimal("input_cost"),
                rs.getBigDecimal("output_cost"),
                rs.getBigDecimal("total_cost"),
                intOrNull(rs, "unpriced_spans"),
                rs.getBoolean("is_settled"),
                rs.getString("input_preview"),
                rs.getString("output_preview"));
    }

    public Optional<TraceV2Row> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM trace WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    private static TraceV2Row map(ResultSet rs) throws SQLException {
        return new TraceV2Row(
                rs.getString("project_id"),
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("parent_trace_id"),
                rs.getString("thread_id"),
                rs.getString("name"),
                rs.getString("user_id"),
                rs.getString("project_version_id"),
                rs.getString("status"),
                requireIso(rs, "started_at"),
                Timestamps.iso(rs, "ended_at"),
                longOrNull(rs, "latency_ms"),
                intOrNull(rs, "span_count"),
                intOrNull(rs, "error_count"),
                longOrNull(rs, "input_tokens"),
                longOrNull(rs, "output_tokens"),
                longOrNull(rs, "cache_read_tokens"),
                longOrNull(rs, "cache_write_tokens"),
                longOrNull(rs, "reasoning_tokens"),
                longOrNull(rs, "total_tokens"),
                numericOrNull(rs, "input_cost"),
                numericOrNull(rs, "output_cost"),
                numericOrNull(rs, "total_cost"),
                intOrNull(rs, "unpriced_spans"),
                rs.getString("input_preview"),
                rs.getString("output_preview"),
                rs.getString("call_site_id"),
                Timestamps.iso(rs, "rollup_due_at"),
                Timestamps.iso(rs, "rolled_up_at"),
                Timestamps.iso(rs, "rolled_up_through"),
                rs.getBoolean("is_settled"),
                rs.getBoolean("has_root_span"),
                requireIso(rs, "event_ts"),
                rs.getBoolean("is_deleted"));
    }

    static @Nullable Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static @Nullable Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static @Nullable String numericOrNull(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.toPlainString();
    }
}
