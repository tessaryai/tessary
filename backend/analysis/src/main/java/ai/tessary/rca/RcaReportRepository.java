// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link RcaReportRow}. Reads join the report's {@code job} row and surface the
 * queue's live status as the report's — the single source for "is it still running", covering the
 * exhaustion-sweep case where only the job is flipped to {@code failed} (see {@link RcaReportRow}).
 */
@Repository
public class RcaReportRepository {

    private static final String COLS = "r.id, r.project_id, r.job_id, r.subject_kind, r.subject_id, "
            + "r.subject_label, r.call_site_id, r.metric, r.window_from, r.window_split, r.window_to, "
            + "r.current_value, r.prior_value, r.delta, j.status AS status, r.verdict, r.summary, "
            + "r.ruled_out, r.hypotheses, r.detailed_report, r.engine, r.repo_available, "
            + "r.created_at, r.completed_at";

    private static final String FROM = "FROM rca_report r JOIN job j ON j.id = r.job_id";

    private final JdbcClient jdbc;

    public RcaReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert the pending report shell for a job, no-op when a concurrent trigger already inserted it
     *  ({@code ux_rca_report_job}) — the report side of {@link RcaJobRepository#createOrGet}'s coalesce.
     *  {@code engine} is stamped here, at trigger time, so the polling UI can describe the running
     *  lane ("cloning your repo…") before the analysis completes. */
    public void insertPendingIfAbsent(
            String projectId,
            String jobId,
            String findingId,
            String subjectKind,
            String subjectId,
            String subjectLabel,
            @Nullable String callSiteId,
            String metric,
            Instant windowFrom,
            Instant windowSplit,
            Instant windowTo,
            double currentValue,
            double priorValue,
            double delta,
            String engine) {
        jdbc.sql("""
                INSERT INTO rca_report (id, project_id, job_id, finding_id, subject_kind, subject_id,
                    subject_label, call_site_id, metric, window_from, window_split, window_to,
                    current_value, prior_value, delta, status, engine, created_at)
                VALUES (:id, :pid, :jobId, :findingId, :subjectKind, :subjectId,
                    :subjectLabel, :callSiteId, :metric, :windowFrom, :windowSplit, :windowTo,
                    :currentValue, :priorValue, :delta, 'pending', :engine, :now)
                ON CONFLICT (job_id) DO NOTHING
                """)
                .param("id", Ids.ulid())
                .param("pid", projectId)
                .param("jobId", jobId)
                .param("findingId", findingId)
                .param("subjectKind", subjectKind)
                .param("subjectId", subjectId)
                .param("subjectLabel", subjectLabel)
                .param("callSiteId", callSiteId)
                .param("metric", metric)
                .param("windowFrom", windowFrom.toString())
                .param("windowSplit", windowSplit.toString())
                .param("windowTo", windowTo.toString())
                .param("currentValue", currentValue)
                .param("priorValue", priorValue)
                .param("delta", delta)
                .param("engine", engine)
                .param("now", Instant.now().toString())
                .update();
    }

    public Optional<RcaReportRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " " + FROM + " WHERE r.project_id = :pid AND r.id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Which finding a report is about — null for a report from before findings were the subject. */
    public Optional<String> findingIdOf(String projectId, String jobId) {
        return jdbc.sql("SELECT finding_id FROM rca_report WHERE project_id = :pid AND job_id = :jobId")
                .param("pid", projectId)
                .param("jobId", jobId)
                .query(String.class)
                .optional();
    }

    /** The most recent reports on one finding, newest first — the case page's "has anyone looked?" read. */
    public List<RcaReportRow> listByFinding(String projectId, String findingId, int limit) {
        return jdbc.sql("SELECT " + COLS + " " + FROM
                        + " WHERE r.project_id = :pid AND r.finding_id = :fid ORDER BY r.created_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("fid", findingId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * What a finished RCA concluded about each of {@code findingIds} — its verdict and the leading
     * hypothesis title — as the Triage queue reads it.
     *
     * <p>ONE query for a whole page, served by {@code ix_rca_report_finding}, rather than a report fetch
     * per row: Triage lists every live case, and a per-row read would put an RCA lookup behind the app's
     * first screen. The two values are pulled straight out instead of mapping {@link RcaReportRow},
     * because building a one-line caption does not justify hydrating hypotheses, a checklist and a
     * markdown report.
     *
     * <p>Only a {@code done} run counts, and {@code done} is the JOB's status for the reason the rest of
     * this file joins {@code job}: an exhaustion sweep flips the job and not the report. A failed or
     * running analysis has concluded nothing, and a row captioned from one would state a cause nobody
     * established.
     */
    public Map<String, CaseLead> leadsByFinding(String projectId, Collection<String> findingIds) {
        if (findingIds.isEmpty()) return Map.of();
        Map<String, CaseLead> out = new HashMap<>();
        jdbc.sql("SELECT DISTINCT ON (r.finding_id) r.finding_id, r.verdict,"
                        + " r.hypotheses -> 0 ->> 'title' AS cause "
                        + FROM
                        + " WHERE r.project_id = :pid AND r.finding_id IN (:fids) AND j.status = 'done'"
                        + " ORDER BY r.finding_id, r.created_at DESC")
                .param("pid", projectId)
                .param("fids", findingIds)
                .query((rs, n) -> out.put(
                        rs.getString("finding_id"), new CaseLead(rs.getString("verdict"), rs.getString("cause"))))
                .list();
        return out;
    }

    /** What a finished RCA gives a case row: its verdict, and the leading hypothesis if it reached one. */
    public record CaseLead(
            @Nullable String verdict, @Nullable String cause) {}

    public Optional<RcaReportRow> findByJobId(String projectId, String jobId) {
        return jdbc.sql("SELECT " + COLS + " " + FROM + " WHERE r.project_id = :pid AND r.job_id = :jobId")
                .param("pid", projectId)
                .param("jobId", jobId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** The project's most recent reports, newest first, bounded by {@code limit}. */
    public List<RcaReportRow> listByProject(String projectId, int limit) {
        return listByProject(projectId, limit, null, null, null, null);
    }

    /**
     * The project's most recent reports, newest first, bounded by {@code limit} and narrowed by any
     * of the optional filters. Filtering happens in SQL, not after the limit — asking for one
     * subject's history must not be silently emptied by newer reports on other subjects. {@code
     * status} filters the live queue status on the joined job, the same column {@link #COLS}
     * projects as the report's status.
     */
    public List<RcaReportRow> listByProject(
            String projectId,
            int limit,
            @Nullable String subjectKind,
            @Nullable String subjectId,
            @Nullable String metric,
            @Nullable String status) {
        return jdbc.sql("SELECT " + COLS + " " + FROM + """
                         WHERE r.project_id = :pid
                           AND (CAST(:subjectKind AS text) IS NULL OR r.subject_kind = :subjectKind)
                           AND (CAST(:subjectId AS text) IS NULL OR r.subject_id = :subjectId)
                           AND (CAST(:metric AS text) IS NULL OR r.metric = :metric)
                           AND (CAST(:status AS text) IS NULL OR j.status = :status)
                         ORDER BY r.created_at DESC LIMIT :limit
                        """)
                .param("pid", projectId)
                .param("subjectKind", subjectKind)
                .param("subjectId", subjectId)
                .param("metric", metric)
                .param("status", status)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Stamp the analysis outcome — the one write a report ever receives after its shell insert. */
    public void complete(
            String jobId,
            String status,
            @Nullable String verdict,
            @Nullable String summary,
            @Nullable String ruledOutJson,
            @Nullable String hypothesesJson,
            @Nullable String detailedReport,
            @Nullable Boolean repoAvailable) {
        jdbc.sql("""
                UPDATE rca_report SET status = :status, verdict = :verdict, summary = :summary,
                    ruled_out = :ruledOut::jsonb, hypotheses = :hypotheses::jsonb,
                    detailed_report = :detailedReport, repo_available = :repoAvailable,
                    completed_at = :now
                WHERE job_id = :jobId
                """)
                .param("status", status)
                .param("verdict", verdict)
                .param("summary", summary)
                .param("ruledOut", ruledOutJson)
                .param("hypotheses", hypothesesJson)
                .param("detailedReport", detailedReport)
                .param("repoAvailable", repoAvailable)
                .param("now", Instant.now().toString())
                .param("jobId", jobId)
                .update();
    }

    /** {@code getBoolean} reads SQL NULL as false, which is the one answer this column must not
     *  invent: a report written before the column existed does not know whether it had a repo. */
    private static @Nullable Boolean readNullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static RcaReportRow map(ResultSet rs) throws SQLException {
        return new RcaReportRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("job_id"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("subject_label"),
                rs.getString("call_site_id"),
                rs.getString("metric"),
                rs.getString("window_from"),
                rs.getString("window_split"),
                rs.getString("window_to"),
                rs.getDouble("current_value"),
                rs.getDouble("prior_value"),
                rs.getDouble("delta"),
                rs.getString("status"),
                rs.getString("verdict"),
                rs.getString("summary"),
                rs.getString("ruled_out"),
                rs.getString("hypotheses"),
                rs.getString("detailed_report"),
                rs.getString("engine"),
                readNullableBoolean(rs, "repo_available"),
                rs.getString("created_at"),
                rs.getString("completed_at"));
    }
}
