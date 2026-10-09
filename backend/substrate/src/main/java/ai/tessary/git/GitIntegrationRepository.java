// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GitIntegrationRepository {

    private static final String COLS = "id, project_id, provider, host, repo_owner, repo_name, default_branch, "
            + "credentials_enc, created_at, updated_at";

    private final JdbcClient jdbc;

    public GitIntegrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GitIntegrationRow> findByProject(String projectId) {
        return jdbc.sql(String.format(Locale.ROOT, "SELECT %s FROM git_integration WHERE project_id = :pid", COLS))
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void insert(GitIntegrationRow row) {
        jdbc.sql("""
            INSERT INTO git_integration (id, project_id, provider, host, repo_owner, repo_name,
                                         default_branch, credentials_enc, created_at, updated_at)
            VALUES (:id, :pid, :prov, :host, :owner, :name, :branch, :creds, :created, :updated)
            """)
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("prov", row.provider())
                .param("host", row.host())
                .param("owner", row.repoOwner())
                .param("name", row.repoName())
                .param("branch", row.defaultBranch())
                .param("creds", row.credentialsEnc())
                .param("created", row.createdAt())
                .param("updated", row.updatedAt())
                .update();
    }

    public boolean deleteByProject(String projectId) {
        return jdbc.sql("DELETE FROM git_integration WHERE project_id = :pid")
                        .param("pid", projectId)
                        .update()
                > 0;
    }

    private static GitIntegrationRow map(ResultSet rs) throws SQLException {
        return new GitIntegrationRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("provider"),
                rs.getString("host"),
                rs.getString("repo_owner"),
                rs.getString("repo_name"),
                rs.getString("default_branch"),
                rs.getString("credentials_enc"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
