// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link CaseRow}. Every write is keyed on the case's identity tuple rather than its
 * id, because the reconciler knows a detection's subject long before it knows whether a case for it
 * already exists.
 *
 * <p><b>Timestamps are ISO-8601 {@code text}, compared and ordered lexicographically</b> — the
 * convention the rca / signal / job slices already use, kept here so the whole platform reads one way.
 * Known and accepted limitation: {@code Instant.toString()} omits a zero seconds field and varies its
 * fraction digits, so {@code …T12:00Z} sorts after {@code …T12:00:01Z}. It needs a timestamp landing on
 * an exact second or an exact millisecond to bite, and it would misplace one row in one window rather
 * than lose it. Seen, measured, left — changing it here alone would fork the convention for one slice.
 */
@Repository
public class CaseRepository {

    private static final String COLS = "id, project_id, seq, detector, subject_kind, subject_id, "
            + "subject_label, call_site_id, metric, finding_id, state, title, basis, severity, onset_at, "
            + "current_value, baseline_value, delta, opened_at, last_seen_at, resolved_at, resolution, "
            + "resolution_reason, resolved_by, muted_at, muted_by, updated_at";

    private static final String LIVE_ORDER = "ORDER BY severity DESC, opened_at DESC";

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- paging vocabulary -------------------------------------------------------------------

    /**
     * How a page of cases is ranked — and therefore what its keyset key means.
     *
     * <p>Two orders rather than one because a live case and a closed one are read for different reasons. A
     * live list answers "what needs me", which is worst-first; a resolved list answers "what happened
     * lately", which is newest-closed-first. Ranking closures by severity would put a serious thing closed
     * in March above a mild thing closed this morning, which is the opposite of what a history is for.
     */
    public enum PageOrder {
        /** Worst first: {@code severity DESC, opened_at DESC, id DESC}. */
        LIVE_RANK,
        /** Newest closure first: {@code resolved_at DESC, id DESC}. */
        RESOLVED_RECENT
    }

    /**
     * The order a {@code state} selection is ranked in. One function, called by both the repository and the
     * cursor codec, because a cursor that encodes one order and a query that runs another resume from a point
     * that is not on the ordering — the failure mode watch-out 2 of the MCP v2 plan exists to prevent.
     *
     * <p>Only a resolved-only page gets the resolved order. A muted page and an unfiltered page both keep the
     * live ranking: {@code severity} and {@code opened_at} are {@code NOT NULL} on every row, so worst-first
     * is total over any selection, while {@code resolved_at} is null on everything that is still open.
     */
    public static PageOrder orderFor(@Nullable String state) {
        return CaseRow.State.RESOLVED.equals(state) ? PageOrder.RESOLVED_RECENT : PageOrder.LIVE_RANK;
    }

    /**
     * Where a page resumes: the last row of the previous page, in the columns its order ranks by.
     *
     * @param severity the live order's first key. Null for {@link PageOrder#RESOLVED_RECENT}, which does not
     *     rank by severity at all; a {@link PageOrder#LIVE_RANK} key arriving without one cannot be resumed
     *     from, and rejecting that is the cursor codec's job — the repository reads what it is handed.
     * @param at {@code opened_at} under the live order, {@code resolved_at} under the resolved one
     * @param id the total tiebreaker. Every column above it can repeat (two cases can open in the same
     *     millisecond at the same severity), and a keyset with a non-unique tail either skips rows or loops.
     */
    public record PageKey(@Nullable Double severity, String at, String id) {}

    // ---- reads -------------------------------------------------------------------------------

    /**
     * One filtered, keyset-paged page of the project's cases — the MCP {@code list_cases} read.
     *
     * <p><b>Ordering runs strictly DESC on every key, including the id tiebreaker, so the keyset is a single
     * row-constructor comparison</b> ({@code (severity, opened_at, id) < (:sev, :at, :id)}) rather than the
     * three-way OR ladder a mixed-direction sort would need. Postgres evaluates that against the index the
     * ordering already uses; the same trick pages spans in {@code SpanKey}. Mixing an ASC tiebreaker into a
     * DESC sort would cost the row constructor and buy nothing a reader would notice.
     *
     * <p><b>No index was added for this and none is missing for the live path.</b>
     * {@code ix_eval_case_live_rank} is {@code (project_id, severity DESC, opened_at DESC) WHERE state <>
     * 'resolved'} — this page's live keyset, already in the shape it needs, and {@code state = 'open'} implies
     * that partial predicate so it applies with only a heap recheck for the state value. A {@code detector}
     * or {@code call_site_id} filter rechecks rather than seeks, which is what {@link #listLiveByDetector}
     * has always done. The resolved order is the one that sorts ({@code ix_eval_case_resolved} buries
     * {@code resolved_at} behind four equality columns), and it is deliberately left sorting: the resolved
     * set is small per project and bounded by the history window, so an index for it waits on EXPLAIN
     * against real data rather than on a guess.
     *
     * @param state {@code open} | {@code muted} | {@code resolved}, or null for every state
     * @param limit rows to return. Callers over-fetch by one to detect a next page.
     * @param before the previous page's last row, or null to start at the top
     */
    public List<CaseRow> page(
            String projectId,
            @Nullable String state,
            @Nullable String detector,
            @Nullable String callSiteId,
            int limit,
            @Nullable PageKey before) {
        PageOrder order = orderFor(state);
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid");
        if (state != null) {
            sql.append(" AND state = :state");
        }
        if (detector != null) {
            sql.append(" AND detector = :detector");
        }
        if (callSiteId != null) {
            sql.append(" AND call_site_id = :callSiteId");
        }
        if (before != null) {
            sql.append(
                    order == PageOrder.LIVE_RANK
                            ? " AND (severity, opened_at, id) < (:beforeSeverity, :beforeAt, :beforeId)"
                            : " AND (resolved_at, id) < (:beforeAt, :beforeId)");
        }
        sql.append(
                order == PageOrder.LIVE_RANK
                        ? " ORDER BY severity DESC, opened_at DESC, id DESC"
                        : " ORDER BY resolved_at DESC, id DESC");
        sql.append(" LIMIT :limit");

        var stmt = jdbc.sql(sql.toString()).param("pid", projectId).param("limit", limit);
        if (state != null) stmt = stmt.param("state", state);
        if (detector != null) stmt = stmt.param("detector", detector);
        if (callSiteId != null) stmt = stmt.param("callSiteId", callSiteId);
        if (before != null) {
            stmt = stmt.param("beforeAt", before.at()).param("beforeId", before.id());
            if (order == PageOrder.LIVE_RANK) {
                stmt = stmt.param("beforeSeverity", before.severity());
            }
        }
        return stmt.query((rs, n) -> map(rs)).list();
    }

    /** The project's live cases — open and muted — worst first. Triage's whole read. */
    public List<CaseRow> listLive(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND state <> 'resolved' "
                        + LIVE_ORDER)
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Cases resolved on or after {@code since}, newest first — Triage's one quiet history line. */
    public List<CaseRow> listResolvedSince(String projectId, Instant since) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND state = 'resolved' "
                        + "AND resolved_at >= :since ORDER BY resolved_at DESC")
                .param("pid", projectId)
                .param("since", since.toString())
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<CaseRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Lookup by the display number a human quotes ({@code C-118} → {@code 118}). */
    public Optional<CaseRow> findBySeq(String projectId, long seq) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND seq = :seq")
                .param("pid", projectId)
                .param("seq", seq)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** The live case on a subject key, if one exists. */
    public Optional<CaseRow> findLive(String projectId, CaseKey key) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND detector = :detector "
                        + "AND subject_kind = :subjectKind AND subject_id = :subjectId AND metric = :metric "
                        + "AND state <> 'resolved'")
                .param("pid", projectId)
                .param("detector", key.detector())
                .param("subjectKind", key.subjectKind())
                .param("subjectId", key.subjectId())
                .param("metric", key.metric())
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * The most recently resolved case on a subject key that closed on or after {@code since} — the
     * reopen candidate. Re-firing inside the window continues the same case (evidence appends to the
     * trail a human has already read); past it, the spell is a new story and earns a fresh number.
     */
    public Optional<CaseRow> findResolvedSince(String projectId, CaseKey key, Instant since) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND detector = :detector "
                        + "AND subject_kind = :subjectKind AND subject_id = :subjectId AND metric = :metric "
                        + "AND state = 'resolved' AND resolved_at >= :since ORDER BY resolved_at DESC LIMIT 1")
                .param("pid", projectId)
                .param("detector", key.detector())
                .param("subjectKind", key.subjectKind())
                .param("subjectId", key.subjectId())
                .param("metric", key.metric())
                .param("since", since.toString())
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Live cases for one detector — the reconciler's sweep set for auto-resolve. */
    public List<CaseRow> listLiveByDetector(String projectId, String detector) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND detector = :detector "
                        + "AND state <> 'resolved' " + LIVE_ORDER)
                .param("pid", projectId)
                .param("detector", detector)
                .query((rs, n) -> map(rs))
                .list();
    }

    // ---- writes ------------------------------------------------------------------------------

    /**
     * Exclude other reconciler passes on this project for the rest of the transaction.
     *
     * <p>{@link #open} allocates the display number as {@code MAX(seq)+1}, and two backends sweeping the
     * same project read that identically before either commits. An advisory lock rather than a counter
     * table or {@code SELECT … FOR UPDATE} on {@code project}: it needs no schema, and it contends with
     * nothing but another reconcile — locking the {@code project} row would block ordinary settings
     * writes for the length of a sweep.
     */
    public void lockProject(String projectId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:pid))")
                .param("pid", projectId)
                .query()
                .singleValue();
    }

    /**
     * Open a fresh case for {@code detection}, allocating the next per-project display number.
     * Returns empty when a concurrent reconciler pass already opened one on this key
     * ({@code ux_eval_case_live}) — the caller then reads the winner and treats the detection as an
     * update, so two workers can never double-page for one degradation.
     *
     * <p>The conflict target is explicit, and must stay that way. A bare {@code ON CONFLICT DO NOTHING}
     * also swallows a {@code ux_eval_case_seq} collision, which is not a benign "someone already has
     * this case" but a dropped detection — indistinguishable from the caller, and silent. Named this
     * way, a seq collision raises instead, and {@link #lockProject} is what stops it happening.
     */
    public Optional<CaseRow> open(String projectId, CaseDetection detection, Instant now) {
        String id = Ids.ulid();
        Instant onsetAt = detection.onsetAt();
        Instant onset = onsetAt == null ? now : onsetAt;
        int inserted = jdbc.sql("""
                INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label,
                    call_site_id, metric, finding_id, state, title, basis, severity, onset_at,
                    current_value, baseline_value, delta, opened_at, last_seen_at, updated_at)
                SELECT :id, :pid, COALESCE(MAX(seq), 0) + 1, :detector, :subjectKind, :subjectId, :subjectLabel,
                    :callSiteId, :metric, :findingId, 'open', :title, :basis, :severity, :onsetAt,
                    :currentValue, :baselineValue, :delta, :now, :now, :now
                FROM eval_case WHERE project_id = :pid
                ON CONFLICT (project_id, detector, subject_kind, subject_id, metric)
                    WHERE state <> 'resolved'
                DO NOTHING
                """)
                .param("id", id)
                .param("pid", projectId)
                .param("detector", detection.key().detector())
                .param("subjectKind", detection.key().subjectKind())
                .param("subjectId", detection.key().subjectId())
                .param("subjectLabel", detection.subjectLabel())
                .param("callSiteId", detection.callSiteId())
                .param("metric", detection.key().metric())
                .param("findingId", detection.findingId())
                .param("title", detection.title())
                .param("basis", detection.basis())
                .param("severity", detection.severity())
                .param("onsetAt", onset.toString())
                .param("currentValue", detection.currentValue())
                .param("baselineValue", detection.baselineValue())
                .param("delta", detection.delta())
                .param("now", now.toString())
                .update();
        return inserted == 0 ? Optional.empty() : findById(projectId, id);
    }

    /**
     * Refresh a live case from the detection that is still firing. The title, basis and numbers track
     * the detection so a case never shows a stale "fell 12 pts" after the fall reached 40; the onset
     * does not move, because the spell is the same spell.
     */
    public void refresh(String projectId, String id, CaseDetection detection, Instant now) {
        jdbc.sql("""
                UPDATE eval_case SET title = :title, basis = :basis, severity = :severity,
                    current_value = :currentValue, baseline_value = :baselineValue, delta = :delta,
                    subject_label = :subjectLabel, finding_id = :findingId,
                    last_seen_at = :now, updated_at = :now
                WHERE project_id = :pid AND id = :id
                """)
                .param("pid", projectId)
                .param("id", id)
                .param("title", detection.title())
                .param("basis", detection.basis())
                .param("severity", detection.severity())
                .param("currentValue", detection.currentValue())
                .param("baselineValue", detection.baselineValue())
                .param("delta", detection.delta())
                .param("subjectLabel", detection.subjectLabel())
                .param("findingId", detection.findingId())
                .param("now", now.toString())
                .update();
    }

    /**
     * Bring a resolved case back to open on the same number, re-stamping it from the new detection.
     * The resolution fields are cleared — the case is live again, and a reader must not see a
     * "resolved by Priya" stamp on something that is currently firing.
     */
    public void reopen(String projectId, String id, CaseDetection detection, Instant now) {
        // Unreachable with a null onset — CaseLedger.isNewSpell only lets a bracketed detection reopen —
        // but the record permits one, and stamping `now` is the same rule open() follows.
        Instant onsetAt = detection.onsetAt();
        Instant onset = onsetAt == null ? now : onsetAt;
        jdbc.sql("""
                UPDATE eval_case SET state = 'open', resolved_at = NULL, resolution = NULL,
                    resolution_reason = NULL, resolved_by = NULL,
                    title = :title, basis = :basis, severity = :severity, onset_at = :onsetAt,
                    current_value = :currentValue, baseline_value = :baselineValue, delta = :delta,
                    subject_label = :subjectLabel, finding_id = :findingId,
                    last_seen_at = :now, updated_at = :now
                WHERE project_id = :pid AND id = :id
                """)
                .param("pid", projectId)
                .param("id", id)
                .param("title", detection.title())
                .param("basis", detection.basis())
                .param("severity", detection.severity())
                .param("onsetAt", onset.toString())
                .param("currentValue", detection.currentValue())
                .param("baselineValue", detection.baselineValue())
                .param("delta", detection.delta())
                .param("subjectLabel", detection.subjectLabel())
                .param("findingId", detection.findingId())
                .param("now", now.toString())
                .update();
    }

    /**
     * Close a case. {@code reason} is required for a human close and null for an auto-resolve; the
     * {@code ck_eval_case_resolution_reason} constraint is the backstop.
     */
    public void resolve(
            String projectId,
            String id,
            String resolution,
            @Nullable String reason,
            @Nullable String actor,
            Instant now) {
        jdbc.sql("""
                UPDATE eval_case SET state = 'resolved', resolved_at = :now, resolution = :resolution,
                    resolution_reason = :reason, resolved_by = :actor, updated_at = :now
                WHERE project_id = :pid AND id = :id AND state <> 'resolved'
                """)
                .param("pid", projectId)
                .param("id", id)
                .param("resolution", resolution)
                .param("reason", reason)
                .param("actor", actor)
                .param("now", now.toString())
                .update();
    }

    /** Mute a live case: it stays the case for its spell, and leaves the default Triage list. */
    public void mute(String projectId, String id, @Nullable String actor, Instant now) {
        jdbc.sql("""
                UPDATE eval_case SET state = 'muted', muted_at = :now, muted_by = :actor, updated_at = :now
                WHERE project_id = :pid AND id = :id AND state = 'open'
                """)
                .param("pid", projectId)
                .param("id", id)
                .param("actor", actor)
                .param("now", now.toString())
                .update();
    }

    public void unmute(String projectId, String id, Instant now) {
        jdbc.sql("""
                UPDATE eval_case SET state = 'open', muted_at = NULL, muted_by = NULL, updated_at = :now
                WHERE project_id = :pid AND id = :id AND state = 'muted'
                """)
                .param("pid", projectId)
                .param("id", id)
                .param("now", now.toString())
                .update();
    }

    // ---- mapping -----------------------------------------------------------------------------

    private static CaseRow map(ResultSet rs) throws SQLException {
        return new CaseRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getLong("seq"),
                rs.getString("detector"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("subject_label"),
                rs.getString("call_site_id"),
                rs.getString("metric"),
                rs.getString("finding_id"),
                rs.getString("state"),
                rs.getString("title"),
                rs.getString("basis"),
                rs.getDouble("severity"),
                rs.getString("onset_at"),
                doubleOrNull(rs, "current_value"),
                doubleOrNull(rs, "baseline_value"),
                doubleOrNull(rs, "delta"),
                rs.getString("opened_at"),
                rs.getString("last_seen_at"),
                rs.getString("resolved_at"),
                rs.getString("resolution"),
                rs.getString("resolution_reason"),
                rs.getString("resolved_by"),
                rs.getString("muted_at"),
                rs.getString("muted_by"),
                rs.getString("updated_at"));
    }

    private static @Nullable Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
