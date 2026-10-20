// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Reads surface the {@code job} row's status as the report's, since an exhaustion sweep flips only the job (see
 * {@link RcaReportRow}).
 */
@Repository
public class RcaReportRepository {

    private static final String COLS = "r.id, r.job_id, r.subject_kind, r.subject_id, "
            + "r.subject_label, r.call_site_id, r.metric, r.report_kind, r.window_from, r.window_split, r.window_to, "
            + "r.current_value, r.prior_value, r.delta, j.status AS status, r.verdict, r.summary, "
            + "r.ruled_out, r.hypotheses, r.causes, r.detailed_report, r.engine, r.repo_available, "
            + "r.created_at, r.completed_at";

    private static final String FROM = "FROM rca_report r JOIN job j ON j.id = r.job_id";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RcaReportRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * No-op when a concurrent trigger already inserted it. {@code engine} is stamped at trigger time so the UI can
     * describe the running lane.
     */
    public void insertPendingIfAbsent(
            String projectId,
            String jobId,
            String findingId,
            String subjectKind,
            String subjectId,
            String subjectLabel,
            @Nullable String callSiteId,
            String metric,
            String reportKind,
            Instant windowFrom,
            Instant windowSplit,
            Instant windowTo,
            double currentValue,
            double priorValue,
            double delta,
            String engine) {
        jdbc.sql("""
                INSERT INTO rca_report (id, project_id, job_id, finding_id, subject_kind, subject_id,
                    subject_label, call_site_id, metric, report_kind, window_from, window_split, window_to,
                    current_value, prior_value, delta, status, engine, created_at)
                VALUES (:id, :pid, :jobId, :findingId, :subjectKind, :subjectId,
                    :subjectLabel, :callSiteId, :metric, :reportKind, :windowFrom, :windowSplit, :windowTo,
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
                .param("reportKind", reportKind)
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

    /** Null for a report from before findings were the subject. */
    public Optional<String> findingIdOf(String projectId, String jobId) {
        return jdbc.sql("SELECT finding_id FROM rca_report WHERE project_id = :pid AND job_id = :jobId")
                .param("pid", projectId)
                .param("jobId", jobId)
                .query(String.class)
                .optional();
    }

    public List<RcaReportRow> listByFinding(String projectId, String findingId, int limit) {
        return jdbc.sql("SELECT " + COLS + " " + FROM
                        + " WHERE r.project_id = :pid AND r.finding_id = :fid ORDER BY r.created_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("fid", findingId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    public record CaseLead(
            @Nullable String verdict, @Nullable String summary) {}

    /**
     * One query per page via {@code ix_rca_report_finding}, keyed on the case through {@code finding.case_id}. Only a
     * {@code done} JOB counts: a failed or running analysis has concluded nothing.
     */
    public Map<String, CaseLead> leadsByCase(String projectId, Collection<String> caseIds) {
        if (caseIds.isEmpty()) return Map.of();
        Map<String, CaseLead> out = new HashMap<>();
        jdbc.sql("SELECT DISTINCT ON (f.case_id) f.case_id, r.verdict, r.summary, r.report_kind, r.causes,"
                        + " r.hypotheses "
                        + FROM + " JOIN finding f ON f.id = r.finding_id"
                        + " WHERE r.project_id = :pid AND f.case_id IN (:caseIds) AND j.status = 'done'"
                        + " ORDER BY f.case_id, r.created_at DESC")
                .param("pid", projectId)
                .param("caseIds", caseIds)
                .query((rs, n) -> {
                    List<Cause> causes = RcaDtos.causesOf(
                            mapper, rs.getString("report_kind"), rs.getString("causes"), rs.getString("hypotheses"));
                    return out.put(
                            rs.getString("case_id"),
                            new CaseLead(rs.getString("verdict"), RcaDtos.summaryOf(rs.getString("summary"), causes)));
                })
                .list();
        return out;
    }

    /**
     * Where a report's analysis stands: its job's status, whether a worker holds its lease, and its newest agent run.
     *
     * @param leased a worker holds the job; a claimed job with no owner is back in the queue @param jobQueuedAt when
     * the job was last asked for @param doing the words of the run's newest step that has not ended
     */
    public record RunState(
            String reportId,
            String jobStatus,
            boolean leased,
            String jobQueuedAt,
            @Nullable String runId,
            @Nullable String startedAt,
            @Nullable String endedAt,
            int stepCount,
            @Nullable String doing,
            @Nullable String stopCode) {}

    private static final String RUN_STATE_COLS = "r.id AS report_id, j.status AS job_status,"
            + " j.lease_owner IS NOT NULL AS leased, COALESCE(j.queued_at, j.created_at) AS job_queued_at,"
            + " a.id AS run_id, a.started_at, a.ended_at, COALESCE(a.step_count, 0) AS step_count, a.stop_code,"
            + " (SELECT s.doing FROM agent_run_step s WHERE s.run_id = a.id AND s.ended_ms IS NULL"
            + " ORDER BY s.seq DESC LIMIT 1) AS doing ";

    private static final String NEWEST_RUN = " LEFT JOIN LATERAL (SELECT x.id, x.started_at, x.ended_at, x.step_count,"
            + " x.stop_code FROM agent_run x WHERE x.project_id = r.project_id AND x.lane = 'rca'"
            + " AND x.subject_id = r.id ORDER BY x.started_at DESC, x.id DESC LIMIT 1) a ON TRUE";

    public Optional<RunState> runState(String projectId, String reportId) {
        return jdbc.sql("SELECT " + RUN_STATE_COLS + FROM + NEWEST_RUN + " WHERE r.project_id = :pid AND r.id = :id")
                .param("pid", projectId)
                .param("id", reportId)
                .query((rs, n) -> runState(rs))
                .optional();
    }

    /** Each case's newest report, any status, in one query; a case never analyzed is absent. */
    public Map<String, RunState> runStatesByCase(String projectId, Collection<String> caseIds) {
        if (caseIds.isEmpty()) return Map.of();
        Map<String, RunState> out = new HashMap<>();
        jdbc.sql("SELECT DISTINCT ON (f.case_id) f.case_id, " + RUN_STATE_COLS + FROM
                        + " JOIN finding f ON f.id = r.finding_id" + NEWEST_RUN
                        + " WHERE r.project_id = :pid AND f.case_id IN (:caseIds)"
                        + " ORDER BY f.case_id, r.created_at DESC")
                .param("pid", projectId)
                .param("caseIds", caseIds)
                .query((rs, n) -> out.put(rs.getString("case_id"), runState(rs)))
                .list();
        return out;
    }

    private static RunState runState(ResultSet rs) throws SQLException {
        return new RunState(
                rs.getString("report_id"),
                rs.getString("job_status"),
                rs.getBoolean("leased"),
                rs.getString("job_queued_at"),
                rs.getString("run_id"),
                rs.getString("started_at"),
                rs.getString("ended_at"),
                rs.getInt("step_count"),
                rs.getString("doing"),
                rs.getString("stop_code"));
    }

    public Optional<RcaReportRow> findByJobId(String projectId, String jobId) {
        return jdbc.sql("SELECT " + COLS + " " + FROM + " WHERE r.project_id = :pid AND r.job_id = :jobId")
                .param("pid", projectId)
                .param("jobId", jobId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** The one write a report receives after its shell insert. */
    public void complete(
            String jobId,
            String status,
            @Nullable String verdict,
            @Nullable String summary,
            @Nullable String ruledOutJson,
            @Nullable String causesJson,
            @Nullable String detailedReport,
            @Nullable Boolean repoAvailable) {
        jdbc.sql("""
                UPDATE rca_report SET status = :status, verdict = :verdict, summary = :summary,
                    ruled_out = :ruledOut::jsonb, causes = :causes::jsonb,
                    detailed_report = :detailedReport, repo_available = :repoAvailable,
                    completed_at = :now
                WHERE job_id = :jobId
                """)
                .param("status", status)
                .param("verdict", verdict)
                .param("summary", summary)
                .param("ruledOut", ruledOutJson)
                .param("causes", causesJson)
                .param("detailedReport", detailedReport)
                .param("repoAvailable", repoAvailable)
                .param("now", Instant.now().toString())
                .param("jobId", jobId)
                .update();
    }

    /** {@code getBoolean} reads NULL as false, which this column must not invent. */
    private static @Nullable Boolean readNullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static RcaReportRow map(ResultSet rs) throws SQLException {
        return new RcaReportRow(
                rs.getString("id"),
                rs.getString("job_id"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("subject_label"),
                rs.getString("call_site_id"),
                rs.getString("metric"),
                rs.getString("report_kind"),
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
                rs.getString("causes"),
                rs.getString("detailed_report"),
                rs.getString("engine"),
                readNullableBoolean(rs, "repo_available"),
                rs.getString("created_at"),
                rs.getString("completed_at"));
    }
}
