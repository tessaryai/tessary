// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import static ai.tessary.evals.storage.SessionRepository.requireIso;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for the trace table and the rollup protocol that maintains it
 * (substrate-model.md §5.1, §7).
 *
 * <p><b>The table is {@code trace}, plainly, as of the teardown changeset (0083).</b> It was created as
 * {@code trace_v2} in 0076 only because the v1 table held that name and every reader in that release
 * still read it. Both facts have expired: the readers moved through M6–M8, the v1 table is dropped, and
 * the rename ran in the same changeset that dropped it. Nothing in the schema says {@code v2} any more.
 *
 * <p><b>The {@code V2} in this class's name is history, not a distinction.</b> There is no second trace
 * repository to tell it apart from — the v1 one was deleted with its table. The type name is left alone
 * deliberately: renaming it touches sixty files, and doing that inside the change that drops six tables
 * would bury the part of the diff that actually needs reading.
 *
 * <h2>The three write shapes, and why they are three</h2>
 *
 * <ul>
 *   <li>{@link #getOrCreate} — identity only, {@code ON CONFLICT DO NOTHING}. Makes {@code fk_span_trace}
 *       satisfiable regardless of arrival order (§6.1) and writes no timing or rollup column.
 *   <li>{@link #applyBatchTimers} — the ONLY in-place update on this row, and it is {@code min}/{@code
 *       max} plus a monotonically-earlier deadline. Idempotent under replay by construction (§7.1).
 *   <li>{@link #claimDue} + {@link #recompute} — the worker. Every sum and count is a REPLACEMENT read
 *       from the trace's spans, never a delta (§7.2). A running sum has no repair path: one double-add is
 *       permanent and undetectable, whereas a full recompute self-heals after any bug.
 * </ul>
 *
 * <p>{@link #reap} is the recovery for the one gap those three leave: a worker that dies between the claim
 * and the write leaves a trace armed for nobody.
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

    public TraceV2Repository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
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

    /**
     * One JDBC batch of {@link #getOrCreate} inserts, in the order given (the caller sorts, see SpanBatchWriter).
     */
    public void getOrCreateAll(List<TraceV2Row> rows) {
        if (rows.isEmpty()) return;
        int[] applied = named.batchUpdate(
                GET_OR_CREATE_SQL, rows.stream().map(TraceV2Repository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    /**
     * Get-or-create a trace, identity fields only (§6.1 step 2).
     *
     * @return true when this call created the row.
     */
    public boolean getOrCreate(TraceV2Row row) {
        return jdbc.sql(GET_OR_CREATE_SQL).paramSource(params(row)).update() > 0;
    }

    /**
     * Traces started in the last {@code since..now} window, across every project on this install — the
     * telemetry heartbeat's {@code trace_volume_bucket} input (devdocs/reference/telemetry-contract.md
     * §1: "rolling 24h average"). Deliberately install-wide, unlike every other query in this class:
     * the ping reports one coarse install-level bucket, never a per-project figure, so there is no
     * {@code project_id} predicate to add.
     */
    public long countStartedSince(Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM trace WHERE started_at >= :since AND NOT is_deleted")
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    /**
     * One trace's contribution from one drained batch, pre-aggregated in memory by the caller.
     *
     * @param minStartedAt the earliest {@code started_at} among the batch's spans for this trace.
     * @param maxEndedAt the latest {@code ended_at}, or null when no span in the batch had ended.
     * @param hasRoot whether any span in the batch had {@code parent_span_id IS NULL}.
     */
    public record TimerUpdate(
            String traceId, String minStartedAt, @Nullable String maxEndedAt, boolean hasRoot) {}

    /**
     * Fold a batch into its traces' timers and re-arm the rollup deadline (§7.1) — the spec's statement,
     * verbatim.
     *
     * <p><b>The deadline only ever moves earlier, never later.</b> That is the entire rule, and
     * {@code LEAST(COALESCE(rollup_due_at, 'infinity'), now() + …)} is what encodes it. A span arriving
     * into an already-armed trace does not postpone the rollup — it only un-settles it. A root span, which
     * means the turn is almost certainly finished, pulls the deadline in to two seconds.
     *
     * <p>This is why <b>no hard cap is needed</b>: a trace that never goes quiet still fires at its
     * deadline, gets re-armed by the next span, and fires again, so a long-running turn is refreshed with
     * current numbers roughly every ten seconds instead of showing nothing until it finishes. And it is
     * why {@code is_settled} stays honest — it can only be set by a rollup that found the deadline still
     * clear, never by a timeout.
     *
     * <p><b>No sums, no counts, no arithmetic beyond min/max.</b> Token and cost totals never ride the
     * write path.
     *
     * <p><b>Sorted-key locking.</b> The {@code FOR UPDATE} pre-pass takes the batch's trace rows in
     * {@code (project_id, id)} order so two concurrent batches over overlapping trace sets can never
     * deadlock. It and the update must share a transaction, and that transaction must be the same one that
     * wrote the spans — the §6.1 atomicity invariant. A span row that became visible without its trace
     * re-arm would be silently excluded from a settling rollup.
     *
     * @return the number of trace rows updated.
     */
    public int applyBatchTimers(String projectId, Collection<TimerUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
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

    /**
     * Give a trace copied from v1 the three things {@link #getOrCreate} deliberately does not write: its
     * end time, whether it has a root span, and a rollup deadline.
     *
     * <p><b>Why this is not just {@link #applyBatchTimers}.</b> That statement arms every trace it touches
     * within ten seconds, which is right for live ingest and catastrophic for a backfill: a million
     * historical traces would all come due at once and the worker would spend hours refusing to keep up
     * while live ingest queued behind them. The caller passes a staggered deadline instead — row number
     * times an interval — so the recompute flood is spread over hours and the traces arriving now stay
     * ahead of the ones from last year.
     *
     * <p><b>{@code coveredThrough} is what makes the stagger safe, and its absence was a real bug.</b> The
     * backfill copies traces in one phase and their spans in a later one, so a deadline armed with the
     * trace fires against however many of its spans happen to have landed by then — which, early in the
     * stagger, is none. Such a trace recomputes to {@code span_count = 0}, and because the recompute finds
     * {@code rollup_due_at} clear it sets {@code is_settled = true}: the worker will not claim it again,
     * the reaper only re-arms UNsettled traces, and a re-run of the backfill would not re-offer a deadline
     * to a trace that has already rolled up. It would have been permanently, silently wrong. (The M5 smoke
     * found 360 of 1752 traces in exactly that state.)
     *
     * <p>So the span phase calls this again with the newest {@code event_ts} it just wrote for the trace,
     * and a deadline is re-offered whenever the last rollup did not already cover that instant —
     * {@code rolled_up_through} being precisely {@code max(event_ts)} as of the recompute. A trace that has
     * never rolled up is stale by definition, which is what the trace phase's null {@code coveredThrough}
     * asks about.
     *
     * <p><b>Idempotent, which the whole job depends on.</b> {@code ended_at} folds through
     * {@code GREATEST}, {@code has_root_span} through {@code OR}, and the deadline is re-offered only to a
     * trace whose rollup genuinely predates its spans — so a second run over a settled, fully-rolled-up
     * trace changes nothing and does not drag it back into the queue. That is what makes "re-run changes
     * nothing" true rather than nearly true.
     *
     * <p><b>{@code is_settled} is cleared only when the deadline is.</b> A trace with work pending is not
     * settled, exactly as {@link #applyBatchTimers} treats a span arriving into a quiet trace; a trace this
     * call leaves alone keeps whatever ingest last said about it.
     *
     * @param coveredThrough the newest span {@code event_ts} this caller has written for the trace, or null
     *     in the trace phase, where no span has been copied yet and the only staleness that can be asserted
     *     is "has never rolled up".
     * @return the number of trace rows updated.
     */
    public int armBackfilled(
            String projectId,
            String traceId,
            @Nullable String endedAt,
            boolean hasRootSpan,
            @Nullable String coveredThrough,
            String rollupDueAt) {
        return jdbc.sql("UPDATE trace t SET"
                        + " ended_at      = GREATEST(t.ended_at, :endedAt::timestamptz),"
                        + " has_root_span = t.has_root_span OR :hasRoot,"
                        + " rollup_due_at = CASE WHEN " + ROLLUP_STALE
                        + "                      THEN COALESCE(t.rollup_due_at, :dueAt::timestamptz)"
                        + "                      ELSE t.rollup_due_at END,"
                        + " is_settled    = CASE WHEN " + ROLLUP_STALE + " THEN false ELSE t.is_settled END"
                        + " WHERE t.project_id = :pid AND t.id = :tid")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("endedAt", endedAt)
                .param("hasRoot", hasRootSpan)
                .param("through", coveredThrough)
                .param("dueAt", rollupDueAt)
                .update();
    }

    /**
     * "This trace's rollup does not yet account for what the backfill has written." Never rolled up at all,
     * or rolled up through an instant older than the newest span the caller just copied into it.
     */
    private static final String ROLLUP_STALE = """
            (t.rolled_up_at IS NULL
             OR (:through::timestamptz IS NOT NULL
                 AND (t.rolled_up_through IS NULL OR t.rolled_up_through < :through::timestamptz)))""";

    /** A claimed trace: the key the recompute runs against. */
    public record Claim(String projectId, String traceId) {}

    /**
     * Claim up to {@code limit} due traces with {@code FOR UPDATE SKIP LOCKED}, so several workers can run
     * concurrently without coordinating (§7.3).
     *
     * <p><b>Claiming clears {@code rollup_due_at}, and that is the correctness crux</b>, not a tidy-up. It
     * is what makes the settle check in {@link #recompute} mean something: from this moment any arriving
     * span re-arms the deadline to a non-null value, in the same transaction as its own row, and the
     * recompute can therefore detect that it happened and decline to mark the trace settled.
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

    /**
     * The outcome of one recompute, for the worker's counters.
     *
     * @param settled whether this write settled the trace — i.e. whether {@code rollup_due_at} was still
     *     null at the moment it landed. A false here on a trace the worker just claimed is a span having
     *     arrived mid-rollup, which is the interesting event, not an error.
     * @param spanCount the trace's span count as written, so a caller can log what the replacement said
     *     without reading the row back.
     */
    public record Recomputed(boolean settled, int spanCount) {}

    /**
     * Recompute one claimed trace's rollups as a REPLACEMENT read from its spans (§7.2/§7.3) — the spec's
     * statement, verbatim, plus the implementation plan's root-span carry-down (§2.2).
     *
     * <p>The aggregate reads one trace through the span primary key prefix {@code (project_id, trace_id)},
     * so it is an indexed scan over rows that are already physically clustered together.
     *
     * <p><b>{@code rollup_due_at} is never written here.</b> The claim already cleared it; if a span has
     * re-armed it since, that value has to survive so the trace fires again with that span included. And
     * {@code t.rollup_due_at IS NULL} reads the pre-update value, so the trace settles only
     * if nothing arrived between the claim and this write. The numbers are correct either way — they are a
     * replacement as of the read, not a delta applied to a prior value, so there is no lost update and a
     * re-fire is always safe.
     *
     * <p><b>The settle predicate is the deadline alone, and deliberately says nothing about span count.</b>
     * An earlier revision added {@code AND agg.span_count > 0}, to stop a trace shell left by a
     * half-committed batch from being marked finished while empty. It did stop that, and replaced it with
     * something worse: a row that can never reach any terminal state, because it can never gain a span and
     * the predicate will refuse it forever. The §7.4 reaper re-arms it, the worker recomputes it, the
     * predicate declines, and the cycle repeats every grace period for the life of the database — observed
     * doing exactly that at 17:36, 17:42 and 17:48 on one corpus.
     *
     * <p>An empty trace is not unfinished, it is abandoned, and a settle predicate is the wrong instrument
     * for saying so. The condition is now prevented instead: {@code SpanBatchWriter.commitBatch} creates the
     * identity row inside the same transaction as the spans it was folded from, so a rolled-back batch
     * leaves nothing behind to settle. Every project that solves this solves it by prevention — Langfuse
     * and Jaeger keep no denormalized trace row at all, Tempo stores it in the same physical record as its
     * spans, and Phoenix, the one other Postgres implementation, get-or-creates the trace inside the same
     * savepoint as the span insert. None of them reaps empty parents on a timer.
     *
     * <p><b>{@code unpriced_spans} counts spans of ANY kind that consumed more than zero tokens.</b> An
     * unpriced embedding or rerank span is spend too, and leaving it out would make the honesty marker
     * lie in exactly the case it exists for. The threshold is {@code > 0} rather than "usage reported at
     * all" because producers emit placeholder spans carrying an explicit {@code input_tokens = 0} and
     * {@code output_tokens = 0}; zero is not null, so the generated {@code total_tokens} is 0 rather than
     * null and those spans used to trip the marker on 23.6% of traces while representing no spend
     * whatsoever — which in turn withheld every one of those traces from cost-drift scoring.
     *
     * <p><b>The previews and the call site are copied down from the root span</b>, not stored by ingest:
     * that is what keeps the traces list a single-table read (spec rule 1) rather than a join to find each
     * row's entry point. They are a replacement like everything else here — gated on {@code has_root_span}
     * so a trace whose root has not landed yet keeps whatever it had rather than being blanked by a
     * rollup that fired between a child and its parent.
     *
     * @return the outcome, or empty if the trace was deleted under the worker.
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
                               call_site_id       = CASE WHEN t.has_root_span THEN root.call_site_id
                                                         ELSE t.call_site_id END,
                               rolled_up_at       = now(),
                               rolled_up_through  = agg.through,
                               is_settled         = (t.rollup_due_at IS NULL)
                          FROM agg LEFT JOIN root ON true
                         WHERE t.project_id = :pid AND t.id = :tid
                        RETURNING t.is_settled, t.span_count
                        """)
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> new Recomputed(rs.getBoolean("is_settled"), rs.getInt("span_count")))
                .optional();
    }

    /**
     * The reaper sweep (§7.4) — the spec's statement, with its five minutes as a parameter.
     *
     * <p>A worker that died between claim and write leaves a fingerprint no legitimate state produces:
     * {@code is_settled = false} with {@code rollup_due_at IS NULL} and no recent {@code rolled_up_at}.
     * Such a trace is armed for nobody and would never fire again on its own — its counters would sit at
     * whatever the last completed rollup wrote, with {@code is_settled = false} correctly saying they are
     * stale and nothing ever making them fresh again.
     *
     * <p>The sweep is idempotent and safe at any frequency. Re-arming a healthy in-flight claim costs one
     * redundant recompute and nothing else, because every rollup is a replacement — recovery cannot
     * corrupt totals, which is the property that makes a blunt sweep the right tool here.
     *
     * @param graceSeconds how long a trace may sit claimed-but-unwritten before it counts as stranded.
     * @return the number of traces re-armed.
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
     * The rollup queue as one row: how many traces are armed, and how far past its deadline the most
     * overdue of them has fallen.
     *
     * @param depth traces with a non-null {@code rollup_due_at}, due or not — served entirely by the
     *     partial index {@code ix_trace_rollup_due}, which stays near-empty by construction.
     * @param overdueMs the age of the oldest DUE deadline, or 0 when nothing is due yet. This is the
     *     number the staleness alarm watches: a queue that is deep but on time is just traffic, whereas one
     *     falling behind means every read surface is quietly serving numbers older than it claims.
     */
    public record RollupQueue(int depth, long overdueMs) {}

    /** Sample the rollup queue. One indexed statement, taken once per reaper sweep. */
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

    /**
     * The {@code rolled_up_through} watermark of each named trace that has one — the reference point the
     * span-lateness histogram (§7.6) measures an arriving span against.
     *
     * <p>Traces that have never rolled up are simply absent from the result rather than mapped to null: a
     * span landing into a trace with no watermark is not late, it is first, and folding those into the
     * histogram would bury the tail the instrumentation exists to expose.
     */
    public Map<String, String> rolledUpThrough(String projectId, Collection<String> traceIds) {
        if (traceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        jdbc.sql("SELECT id, rolled_up_through FROM trace"
                        + " WHERE project_id = :pid AND id IN (:ids) AND rolled_up_through IS NOT NULL")
                .param("pid", projectId)
                .param("ids", List.copyOf(traceIds))
                .query((rs, n) -> {
                    String through = Timestamps.iso(rs, "rolled_up_through");
                    if (through != null) out.put(rs.getString("id"), through);
                    return rs.getString("id");
                })
                .list();
        return out;
    }

    // ---- The list surface (substrate-model.md rule 1) ------------------------------------------

    /**
     * Server-side filters for the traces list. Every field is optional; a null or blank one contributes no
     * predicate, so they compose with AND.
     *
     * <p><b>{@code model}, {@code kind} and {@code callSite} are semi-joins, not aggregations.</b> Each
     * becomes {@code EXISTS (SELECT 1 FROM span …)} — "keep this trace when any of its spans matches" —
     * which is a filter the planner can satisfy from an index and stop at the first hit. The v1 shape asked
     * the same question with {@code bool_or(...)} over a {@code GROUP BY} of every observation in the
     * project, which is the aggregation this schema exists to delete.
     *
     * <p>{@code status} reads the rollup: {@code error} means {@code error_count > 0}, {@code ok} means it
     * is zero. A trace that has never rolled up has a NULL {@code error_count} and is therefore neither —
     * it is excluded by an explicit status filter rather than silently counted as healthy.
     */
    public record TraceQuery(
            @Nullable String model,
            @Nullable String kind,
            @Nullable String callSite,
            @Nullable String from,
            @Nullable String to,
            @Nullable String status,
            @Nullable String q) {

        /** The unfiltered query — every field absent. */
        public static TraceQuery none() {
            return new TraceQuery(null, null, null, null, null, null, null);
        }
    }

    /**
     * One trace's list row: identity, timing, and the rollup columns exactly as the worker wrote them.
     *
     * <p><b>Every number here was computed by {@link #recompute}, not by the statement that served this
     * row.</b> That is the whole point of the schema: the list is a filter, a sort and a page over columns
     * that already hold their answers. A null token or cost column means one of two different things, and
     * the wire keeps them apart — {@code isSettled = false} says "not rolled up yet", whereas settled with
     * a null total says "no span reported usage". {@code unpricedSpans} says the third thing: the total is
     * real but incomplete, because some span ran a model we hold no rate for.
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

    /** Sort keys the list accepts. Anything else falls back to {@link #WHEN}. */
    public static final class Sort {
        public static final String WHEN = "when";
        public static final String TOKENS = "tokens";
        public static final String COST = "cost";
        public static final String LATENCY = "latency";

        private Sort() {}
    }

    /**
     * The rollup column a sort key orders on, or null for the default time ordering.
     *
     * <p>All three are plain indexed columns on the listed row. There is no expression to evaluate, no
     * subquery to run per row, and — critically — no {@code COALESCE} to make the key NULL-free: a trace
     * that has not rolled up yet has no total, and pretending it has one of zero would sort every
     * in-flight turn to the cheapest end of the page.
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
     * A project's traces: filter, sort, page. One table, no join to {@code span} except as a semi-join
     * filter, and not one arithmetic operation over the listed rows.
     *
     * <p><b>Keyset pagination.</b> The key is {@code (started_at, id)} for the default ordering, and
     * {@code (<sort column>, started_at, id)} for the rollup sorts. The pair is unique and stable, so
     * paging never skips or repeats a row however many traces share an instant.
     *
     * <p><b>NULLS LAST, and what that costs the cursor.</b> A never-rolled-up trace has no total, and it
     * belongs at the end of a cost or token sort rather than at either extreme of it. Postgres orders
     * {@code DESC} with nulls FIRST by default, so the ordering says {@code DESC NULLS LAST} explicitly and
     * the keyset predicate has two branches: while the cursor still holds a value, the page continues
     * through smaller values and then into the null tail; once the cursor is in the null tail, only the
     * {@code (started_at, id)} tiebreak applies. Collapsing that into one branch with a sentinel is what
     * the v1 query did ({@code COALESCE(cost, -1)}), and it made a genuinely unpriced trace indistinguishable
     * from one priced at minus a dollar.
     *
     * @param beforeSort the previous page's last row's sort value, or null when that row had none (or when
     *     the sort has no column). Only meaningful together with {@code beforeStartedAt}/{@code beforeId}.
     */
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

        addEq(where, params, " AND t.started_at >= :fromTs::timestamptz", "fromTs", query.from());
        addEq(where, params, " AND t.started_at <= :toTs::timestamptz", "toTs", query.to());

        addExists(where, params, "provided_model_name", "model", query.model());
        addExists(where, params, "kind", "kind", query.kind());
        addExists(where, params, "call_site_id", "callSite", query.callSite());

        String status = query.status();
        if (status != null && !status.isBlank()) {
            // A trace with a NULL error_count has not rolled up and therefore has no answer to this
            // question; it is excluded rather than counted as healthy.
            where.append(" AND t.error_count IS NOT NULL AND t.error_count ")
                    .append("error".equalsIgnoreCase(status) ? "> 0" : "= 0");
        }

        String q = query.q();
        if (q != null && !q.isBlank()) {
            where.append(" AND (t.name ILIKE :q OR t.session_id ILIKE :q OR t.thread_id ILIKE :q"
                    + " OR t.user_id ILIKE :q OR t.id ILIKE :q)");
            params.put("q", "%" + q + "%");
        }

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
    }

    /**
     * The ids of the traces matching a filter, newest first — and nothing else: no counts, no sums, no
     * previews. The dataset-snapshot materializer is the caller, and a snapshot only ever used the id.
     *
     * <p>Filter semantics are exactly {@link #list}'s, so a snapshot contains what the Explore page shows
     * for the same filter.
     */
    public List<String> listIdsMatching(String projectId, TraceQuery query, int limit) {
        var params = new HashMap<String, Object>();
        params.put("pid", projectId);
        params.put("limit", limit);

        var where = new StringBuilder("WHERE t.project_id = :pid AND NOT t.is_deleted");
        addEq(where, params, " AND t.started_at >= :fromTs::timestamptz", "fromTs", query.from());
        addEq(where, params, " AND t.started_at <= :toTs::timestamptz", "toTs", query.to());

        addExists(where, params, "provided_model_name", "model", query.model());
        addExists(where, params, "kind", "kind", query.kind());
        addExists(where, params, "call_site_id", "callSite", query.callSite());

        String status = query.status();
        if (status != null && !status.isBlank()) {
            where.append(" AND t.error_count IS NOT NULL AND t.error_count ")
                    .append("error".equalsIgnoreCase(status) ? "> 0" : "= 0");
        }

        String q = query.q();
        if (q != null && !q.isBlank()) {
            where.append(" AND (t.name ILIKE :q OR t.session_id ILIKE :q OR t.thread_id ILIKE :q"
                    + " OR t.user_id ILIKE :q OR t.id ILIKE :q)");
            params.put("q", "%" + q + "%");
        }

        return jdbc.sql("SELECT t.id FROM trace t " + where + " ORDER BY t.started_at DESC, t.id DESC LIMIT :limit")
                .params(params)
                .query(String.class)
                .list();
    }

    /**
     * {@code EXISTS (SELECT 1 FROM span …)} on one span column — a filter, never an aggregation.
     *
     * <p>The correlated predicate carries {@code project_id} as well as {@code trace_id} because the span
     * primary key leads with the project: without it the subquery would scan by trace id alone, which is
     * both slower and one typo away from crossing a project boundary.
     */
    private static void addExists(
            StringBuilder where, Map<String, Object> params, String column, String name, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        where.append(" AND EXISTS (SELECT 1 FROM span sx WHERE sx.project_id = t.project_id"
                + " AND sx.trace_id = t.id AND NOT sx.is_deleted AND sx." + column + " = :" + name + ")");
        params.put(name, value);
    }

    /** Append a clause and bind its parameter, but only when {@code value} is present. */
    private static void addEq(
            StringBuilder clause, Map<String, Object> params, String fragment, String name, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        clause.append(fragment);
        params.put(name, value);
    }

    /**
     * One trace by producer id — the detail read.
     *
     * <p>Returns the same {@link Summary} shape the list serves, so the detail header and the list row can
     * never disagree about a trace's numbers: they are literally the same projection of the same columns.
     */
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

    /**
     * A session's traces, oldest first — the read that stands in for the session rollup this schema
     * deliberately does not have (§7.5). Served by {@code ix_trace_session}.
     */
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

    /**
     * A session's totals: the SUM of its traces' already-materialized rollup columns, plus the count of
     * those that have not settled (§7.5).
     *
     * <p>This is the one place the spec permits a read to add numbers up, and it is permitted precisely
     * because the addends are stored columns of up to ~1,000 indexed rows rather than a scan over spans.
     * {@code unsettledTraces} is the honesty device that goes with it: a total summed from traces still
     * receiving spans is a lower bound, and the wire says so rather than letting the reader assume
     * otherwise.
     */
    public record SessionTotals(
            int traceCount,
            int unsettledTraces,
            @Nullable Long totalTokens,
            @Nullable BigDecimal totalCost,
            @Nullable Long spanCount,
            @Nullable Long errorCount,
            @Nullable Long unpricedSpans) {}

    /** One indexed read over {@code ix_trace_session}. */
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

    /** One session's batched totals row — the multi-session sibling of {@link SessionTotals}. */
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

    /**
     * {@link #sessionTotals}, for a whole page of sessions in one query instead of one per row. Still the
     * same permitted shape — a GROUP BY over up to {@code sessionIds.size()} indexed session partitions of
     * {@code ix_trace_session}, not a scan over spans — because the sessions being summed are already the
     * page a recency-ordered list chose, never a sort key themselves (§7.5).
     *
     * <p>Sessions with no traces yet (a session row can exist via {@code getOrCreate} before its first trace
     * lands) are simply absent from the GROUP BY result; the caller fills in a zeroed row rather than this
     * method returning one, so the SQL stays a plain GROUP BY with no LEFT JOIN against the id list.
     */
    public Map<String, SessionTotalsRow> sessionTotalsForIds(String projectId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
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

    /** One (session, call_site) pair's trace count and most-recent sighting — the input to "dominant call site". */
    public record CallSiteCount(String sessionId, String callSiteId, long n, String lastSeenAt) {}

    /**
     * How often each call site appears in each of {@code sessionIds}' traces, and when it was last seen.
     * Untagged traces ({@code call_site_id IS NULL}) don't count toward any call site. The caller picks the
     * highest {@code n} per session (ties broken by {@code lastSeenAt}) to name the session's dominant agent.
     */
    public List<CallSiteCount> callSiteFrequencyForIds(String projectId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
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
     * The opening line of each of {@code sessionIds}' sessions — its first trace's own input preview,
     * by {@code started_at}. Paired with {@link #lastOutputPreviewForIds}, this is the "first input,
     * last output" bracket a session's Input/Output columns show: neither is a real aggregate (a
     * session has many inputs and many outputs), but the first ask and the most recent answer are the
     * two single values that actually mean something read alone. {@code DISTINCT ON} rides the same
     * {@code ix_trace_session (project_id, session_id, started_at)} index the totals queries use — an
     * index-ordered skip, not a sort of every row.
     */
    public Map<String, String> firstInputPreviewForIds(String projectId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
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

    /** The most recent trace's output preview per session — see {@link #firstInputPreviewForIds}. */
    public Map<String, String> lastOutputPreviewForIds(String projectId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
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

    /** A project's traces, newest first — the list surface's read, served by {@code ix_trace_project_started}. */
    public List<TraceV2Row> listByProject(String projectId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM trace WHERE project_id = :pid AND NOT is_deleted"
                        + " ORDER BY started_at DESC, id DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
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
