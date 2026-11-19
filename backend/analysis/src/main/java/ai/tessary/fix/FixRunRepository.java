// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.fix.FixRunRow.Status;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code fix_run} and its queue: a run is queued, claimed into running, then done or failed. One run per RCA report;
 * a queued run waits while another run of its case is running ({@code ux_fix_run_running_case}).
 */
@Repository
public class FixRunRepository {

    private static final String COLS = """
            r.id, r.project_id, r.fix_pr_id, r.case_id, r.rca_report_id, r.status, r.outcome, r.reason, r.note,
            ARRAY(SELECT jsonb_array_elements_text(r.causes)) AS cause_keys,
            ARRAY(SELECT c.e->>'command' FROM jsonb_array_elements(r.checks) WITH ORDINALITY AS c(e, i)
                   ORDER BY c.i) AS check_commands,
            ARRAY(SELECT c.e->>'result' FROM jsonb_array_elements(r.checks) WITH ORDINALITY AS c(e, i)
                   ORDER BY c.i) AS check_results,
            ARRAY(SELECT c.e->>'note' FROM jsonb_array_elements(r.checks) WITH ORDINALITY AS c(e, i)
                   ORDER BY c.i) AS check_notes,
            r.patch_files, r.created_at, r.updated_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public FixRunRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** A run waiting for the worker. Empty when the report already has a run. */
    public Optional<String> enqueue(
            String projectId, String caseId, String rcaReportId, Collection<String> causes, Instant now) {
        return insert(projectId, caseId, rcaReportId, Status.QUEUED, null, null, causes, now);
    }

    /** A run a shut gate stopped before any agent ran. Empty when the report already has a run. */
    public Optional<String> skip(
            String projectId,
            String caseId,
            String rcaReportId,
            String reason,
            Collection<String> causes,
            Instant now) {
        return insert(projectId, caseId, rcaReportId, Status.DONE, FixRunRow.Outcome.SKIPPED, reason, causes, now);
    }

    private Optional<String> insert(
            String projectId,
            String caseId,
            String rcaReportId,
            String status,
            @Nullable String outcome,
            @Nullable String reason,
            Collection<String> causes,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO fix_run (id, project_id, case_id, rca_report_id, status, outcome, reason, causes,
                                     created_at, updated_at)
                VALUES (:id, :pid, :caseId, :report, :status, :outcome, :reason, CAST(:causes AS jsonb), :now, :now)
                ON CONFLICT (rca_report_id) DO NOTHING
                RETURNING id
                """)
                .param("id", Ids.ulid())
                .param("pid", projectId)
                .param("caseId", caseId)
                .param("report", rcaReportId)
                .param("status", status)
                .param("outcome", outcome)
                .param("reason", reason)
                .param("causes", mapper.valueToTree(List.copyOf(causes)).toString())
                .param("now", now.toString())
                .query(String.class)
                .optional();
    }

    /**
     * The oldest queued run whose case has no running run, now running. Two workers that pick runs of one case at
     * once collide on {@code ux_fix_run_running_case}, and the loser's claim throws.
     */
    public Optional<FixRunRow> claimNext(Instant now) {
        return jdbc.sql("""
                WITH next AS (
                    SELECT q.id FROM fix_run q
                     WHERE q.status = 'queued'
                       AND NOT EXISTS (SELECT 1 FROM fix_run b WHERE b.case_id = q.case_id AND b.status = 'running')
                     ORDER BY q.created_at, q.id
                     LIMIT 1
                       FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE fix_run u SET status = 'running', updated_at = :now FROM next WHERE u.id = next.id
                    RETURNING u.*
                )
                SELECT""" + " " + COLS + " FROM claimed r")
                .param("now", now.toString())
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Running runs last claimed before {@code cutoff}: their worker stopped. */
    public List<FixRunRow> runningSince(Instant cutoff) {
        return jdbc.sql("SELECT " + COLS + " FROM fix_run r WHERE r.status = 'running' AND r.updated_at < :cutoff"
                        + " ORDER BY r.updated_at, r.id")
                .param("cutoff", cutoff.toString())
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Back to the queue: nothing failed, the launcher had no free session. */
    public void requeue(String id, Instant now) {
        jdbc.sql("UPDATE fix_run SET status = 'queued', updated_at = :now WHERE id = :id AND status = 'running'")
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    /** The causes this run works on, once it knows which. */
    public void recordCauses(String id, Collection<String> causes, Instant now) {
        jdbc.sql("UPDATE fix_run SET causes = CAST(:causes AS jsonb), updated_at = :now WHERE id = :id")
                .param("causes", mapper.valueToTree(List.copyOf(causes)).toString())
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    /** The run is done: how it ended, and what it left behind. */
    public void finish(String id, Finish finish, Instant now) {
        jdbc.sql("""
                UPDATE fix_run SET status = 'done', outcome = :outcome, reason = :reason, note = :note,
                                   fix_pr_id = COALESCE(:pr, fix_pr_id), checks = CAST(:checks AS jsonb),
                                   patch_files = :files, updated_at = :now
                 WHERE id = :id
                """)
                .param("outcome", finish.outcome())
                .param("reason", finish.reason())
                .param("note", finish.note())
                .param("pr", finish.fixPrId())
                .param("checks", mapper.valueToTree(finish.checks()).toString())
                .param("files", finish.patchFiles())
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    /** @param reason an error code, or a {@link FixRunRow.Reason} */
    public void fail(String id, String reason, Instant now) {
        jdbc.sql("UPDATE fix_run SET status = 'failed', reason = :reason, updated_at = :now WHERE id = :id")
                .param("reason", reason)
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    public Optional<FixRunRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM fix_run r WHERE r.project_id = :pid AND r.id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** The case's newest run, any status. */
    public Optional<FixRunRow> latestByCase(String projectId, String caseId) {
        return jdbc.sql("SELECT " + COLS + " FROM fix_run r WHERE r.project_id = :pid AND r.case_id = :caseId"
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT 1")
                .param("pid", projectId)
                .param("caseId", caseId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * How a done run ended.
     *
     * @param fixPrId the pull request it opened or added to; null leaves the run's link as it was
     */
    public record Finish(
            String outcome,
            @Nullable String reason,
            @Nullable String note,
            @Nullable String fixPrId,
            List<FixCheck> checks,
            int patchFiles) {

        public Finish {
            checks = List.copyOf(checks);
        }
    }

    private static FixRunRow map(ResultSet rs) throws SQLException {
        List<String> commands = strings(rs, "check_commands");
        List<String> results = strings(rs, "check_results");
        List<String> notes = strings(rs, "check_notes");
        List<FixCheck> checks = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            checks.add(new FixCheck(commands.get(i), results.get(i), notes.get(i)));
        }
        return new FixRunRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("fix_pr_id"),
                rs.getString("case_id"),
                rs.getString("rca_report_id"),
                rs.getString("status"),
                rs.getString("outcome"),
                rs.getString("reason"),
                rs.getString("note"),
                strings(rs, "cause_keys"),
                checks,
                rs.getInt("patch_files"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    /** A text array column; its elements may be null. */
    static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return Arrays.asList((String[]) array.getArray());
    }
}
