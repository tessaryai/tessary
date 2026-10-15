// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.finding.FindingRepository;
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
 * Writes are keyed on the case's identity tuple rather than its id. Timestamps are ISO-8601 text compared
 * lexicographically, the platform convention; {@code Instant.toString()} omitting zero seconds can misplace (never
 * lose) a row at an exact-second boundary, accepted rather than forking the convention.
 */
@Repository
public class CaseRepository {

    /**
     * {@code finding_count} and {@code latest_finding_id} read the reverse pointer {@code finding.case_id}; the newest
     * finding stands in for the case.
     */
    private static final String COLS = "id, seq, detector, subject_kind, subject_id, "
            + "subject_label, call_site_id, metric, "
            + "(SELECT COUNT(*) FROM finding f WHERE f.case_id = eval_case.id) AS finding_count, "
            + "(SELECT f.id FROM finding f WHERE f.case_id = eval_case.id ORDER BY f.created_at DESC LIMIT 1)"
            + " AS latest_finding_id, "
            + "state, locked_at, title, basis, severity, onset_at, "
            + "current_value, baseline_value, delta, opened_at, last_seen_at, resolved_at, resolution, "
            + "resolution_reason, resolved_by, disposition";

    private static final String LIVE_ORDER = "ORDER BY severity DESC, opened_at DESC";

    private final JdbcClient jdbc;
    private final FindingRepository findings;

    public CaseRepository(JdbcClient jdbc, FindingRepository findings) {
        this.jdbc = jdbc;
        this.findings = findings;
    }

    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM eval_case").query(Long.class).single();
    }

    /**
     * A live list is worst-first; a resolved list is newest-closed-first, since ranking closures by severity would put
     * March's serious close above this morning's mild one.
     */
    public enum PageOrder {
        LIVE_RANK,
        RESOLVED_RECENT
    }

    /**
     * One function for both the repository and the cursor codec, so a cursor never resumes on a different order than
     * the query runs. Only resolved-only pages take the resolved order: {@code resolved_at} is null on open rows.
     */
    public static PageOrder orderFor(@Nullable String state) {
        return CaseRow.State.RESOLVED.equals(state) ? PageOrder.RESOLVED_RECENT : PageOrder.LIVE_RANK;
    }

    /**
     * @param severity null under RESOLVED_RECENT; the cursor codec rejects a LIVE_RANK key without one @param id the
     * total tiebreaker: a keyset with a non-unique tail skips rows or loops
     */
    public record PageKey(@Nullable Double severity, String at, String id) {}

    /**
     * Strictly DESC on every key including the id, so the keyset is one row-constructor comparison served by
     * {@code ix_eval_case_live_rank}. The resolved order deliberately still sorts: the set is small per project, so an
     * index waits on EXPLAIN against real data. @param limit callers over-fetch by one to detect a next page
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

    public List<CaseRow> listLive(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND state <> 'resolved' "
                        + LIVE_ORDER)
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

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

    public Optional<CaseRow> findBySeq(String projectId, long seq) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND seq = :seq")
                .param("pid", projectId)
                .param("seq", seq)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * A locked case (RCA pressed) never matches, so the next positive opens a fresh case rather than joining one an
     * agent may be reading.
     */
    public Optional<CaseRow> findLive(String projectId, CaseKey key) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case WHERE project_id = :pid AND detector = :detector "
                        + "AND subject_kind = :subjectKind AND subject_id = :subjectId AND metric = :metric "
                        + "AND state <> 'resolved' AND locked_at IS NULL")
                .param("pid", projectId)
                .param("detector", key.detector())
                .param("subjectKind", key.subjectKind())
                .param("subjectId", key.subjectId())
                .param("metric", key.metric())
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * Empty when a concurrent opener claimed {@code ux_eval_case_live}; the caller joins the winner. The conflict
     * target must stay explicit: a bare {@code ON CONFLICT DO NOTHING} would also swallow a {@code ux_eval_case_seq}
     * collision, silently dropping a detection.
     */
    public Optional<CaseRow> open(String projectId, CaseDetection detection, Instant now) {
        String id = Ids.ulid();
        Instant onsetAt = detection.onsetAt();
        Instant onset = onsetAt == null ? now : onsetAt;
        int inserted = jdbc.sql("""
                INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label,
                    call_site_id, metric, state, title, basis, severity, onset_at,
                    current_value, baseline_value, delta, opened_at, last_seen_at, updated_at)
                SELECT :id, :pid, COALESCE(MAX(seq), 0) + 1, :detector, :subjectKind, :subjectId, :subjectLabel,
                    :callSiteId, :metric, 'open', :title, :basis, :severity, :onsetAt,
                    :currentValue, :baselineValue, :delta, :now, :now, :now
                FROM eval_case WHERE project_id = :pid
                ON CONFLICT (project_id, detector, subject_kind, subject_id, metric)
                    WHERE state <> 'resolved' AND locked_at IS NULL
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
                .param("title", detection.title())
                .param("basis", detection.basis())
                .param("severity", detection.severity())
                .param("onsetAt", onset.toString())
                .param("currentValue", detection.currentValue())
                .param("baselineValue", detection.baselineValue())
                .param("delta", detection.delta())
                .param("now", now.toString())
                .update();
        if (inserted == 0) return Optional.empty();
        link(projectId, detection, id, now);
        return findById(projectId, id);
    }

    /** The title and numbers track the detection; the onset does not move, because the spell is the same spell. */
    public void refresh(String projectId, String id, CaseDetection detection, Instant now) {
        jdbc.sql("""
                UPDATE eval_case SET title = :title, basis = :basis, severity = :severity,
                    current_value = :currentValue, baseline_value = :baselineValue, delta = :delta,
                    subject_label = :subjectLabel,
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
                .param("now", now.toString())
                .update();
        link(projectId, detection, id, now);
    }

    /** A no-op past the first call: re-pressing must not re-stamp the lock time. */
    public void lock(String projectId, String id, Instant now) {
        jdbc.sql("""
                UPDATE eval_case SET locked_at = :now, updated_at = :now
                WHERE project_id = :pid AND id = :id AND locked_at IS NULL
                """)
                .param("pid", projectId)
                .param("id", id)
                .param("now", now.toString())
                .update();
    }

    /** The {@code case_id IS NULL} guard means the first case to claim a finding keeps it. */
    private void link(String projectId, CaseDetection detection, String caseId, Instant now) {
        findings.attachToCase(projectId, detection.findingId(), caseId, now.toString());
    }

    /**
     * {@code reason} is required for a human close and null for an auto-resolve; {@code ck_eval_case_resolution_reason}
     * is the backstop.
     */
    public void resolve(
            String projectId,
            String id,
            String resolution,
            @Nullable String reason,
            @Nullable String actor,
            Instant now) {
        resolve(projectId, id, resolution, reason, actor, null, now);
    }

    public void resolve(
            String projectId,
            String id,
            String resolution,
            @Nullable String reason,
            @Nullable String actor,
            @Nullable String disposition,
            Instant now) {
        jdbc.sql("""
                UPDATE eval_case SET state = 'resolved', resolved_at = :now, resolution = :resolution,
                    resolution_reason = :reason, resolved_by = :actor, disposition = :disposition,
                    updated_at = :now
                WHERE project_id = :pid AND id = :id AND state <> 'resolved'
                """)
                .param("pid", projectId)
                .param("id", id)
                .param("resolution", resolution)
                .param("reason", reason)
                .param("actor", actor)
                .param("disposition", disposition)
                .param("now", now.toString())
                .update();
    }

    private static CaseRow map(ResultSet rs) throws SQLException {
        return new CaseRow(
                rs.getString("id"),
                rs.getLong("seq"),
                rs.getString("detector"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("subject_label"),
                rs.getString("call_site_id"),
                rs.getString("metric"),
                rs.getLong("finding_count"),
                rs.getString("latest_finding_id"),
                rs.getString("state"),
                rs.getString("locked_at"),
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
                rs.getString("disposition"));
    }

    private static @Nullable Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
