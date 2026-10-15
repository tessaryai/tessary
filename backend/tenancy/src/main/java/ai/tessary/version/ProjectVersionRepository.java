// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectVersionRepository {

    private static final String COLS = "id, project_id, commit_sha, parent_sha, materialized_reason, "
            + "graders_status, datasets_status, benchmark_status, summary, created_at, updated_at";

    private final JdbcClient jdbc;

    public ProjectVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lazily materialize the version for a commit. First-writer-wins via
     * {@code ON CONFLICT DO NOTHING} so two callers racing on the same SHA
     * never duplicate or clobber the row; the materialized_reason is whichever
     * caller won the insert.
     */
    public ProjectVersionRow findOrMaterialize(String projectId, String commitSha, String reason) {
        String now = Instant.now().toString();
        jdbc.sql("""
            INSERT INTO project_version (id, project_id, commit_sha, parent_sha, materialized_reason,
                                         graders_status, datasets_status, benchmark_status, summary,
                                         created_at, updated_at)
            VALUES (:id, :pid, :sha, NULL, :reason, 'unknown', 'unknown', 'unknown', NULL, :now, :now)
            ON CONFLICT (project_id, commit_sha) DO NOTHING
            """)
                .param("id", Ids.ulid())
                .param("pid", projectId)
                .param("sha", commitSha)
                .param("reason", reason)
                .param("now", now)
                .update();
        return findByCommit(projectId, commitSha)
                .orElseThrow(() -> new IllegalStateException(
                        "project_version vanished after upsert: " + projectId + "@" + commitSha));
    }

    public Optional<ProjectVersionRow> findByCommit(String projectId, String commitSha) {
        return jdbc.sql(String.format(Locale.ROOT, """
            SELECT %s FROM project_version WHERE project_id = :pid AND commit_sha = :sha
            """, COLS))
                .param("pid", projectId)
                .param("sha", commitSha)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Set one aspect column. {@code column} is a fixed enum supplied by the service, never user input. */
    public void setAspectStatus(String projectId, String commitSha, Aspect aspect, String status) {
        jdbc.sql(String.format(Locale.ROOT, """
            UPDATE project_version SET %s = :status, updated_at = :now
            WHERE project_id = :pid AND commit_sha = :sha
            """, aspect.column))
                .param("status", status)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("sha", commitSha)
                .update();
    }

    /** The per-version sync aspects; each maps to a fixed column name. */
    public enum Aspect {
        GRADERS("graders_status");

        private final String column;

        Aspect(String column) {
            this.column = column;
        }
    }

    private static ProjectVersionRow map(ResultSet rs) throws SQLException {
        return new ProjectVersionRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("commit_sha"),
                rs.getString("parent_sha"),
                rs.getString("materialized_reason"),
                rs.getString("graders_status"),
                rs.getString("datasets_status"),
                rs.getString("benchmark_status"),
                rs.getString("summary"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
