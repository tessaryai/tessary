// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code fix_pr}: at most one open pull request per case ({@code ux_fix_pr_open_case}). */
@Repository
public class FixPrRepository {

    private static final String COLS = "id, project_id, case_id, branch, pr_number, pr_url, state, head_sha,"
            + " ARRAY(SELECT jsonb_array_elements_text(causes)) AS cause_keys, opened_at, updated_at";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public FixPrRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** An open pull request Tessary just opened. Returns its id. */
    public String insertOpen(
            String projectId,
            String caseId,
            String branch,
            int prNumber,
            String prUrl,
            String headSha,
            Collection<String> causes,
            Instant now) {
        String id = Ids.ulid();
        jdbc.sql("""
                INSERT INTO fix_pr (id, project_id, case_id, branch, pr_number, pr_url, state, head_sha, causes,
                                    opened_at, updated_at)
                VALUES (:id, :pid, :caseId, :branch, :number, :url, 'open', :head, CAST(:causes AS jsonb), :now, :now)
                """)
                .param("id", id)
                .param("pid", projectId)
                .param("caseId", caseId)
                .param("branch", branch)
                .param("number", prNumber)
                .param("url", prUrl)
                .param("head", headSha)
                .param("causes", json(causes))
                .param("now", now.toString())
                .update();
        return id;
    }

    /** Newest first. */
    public List<FixPrRow> listByCase(String projectId, String caseId) {
        return jdbc.sql("SELECT " + COLS + " FROM fix_pr WHERE project_id = :pid AND case_id = :caseId"
                        + " ORDER BY opened_at DESC, id DESC")
                .param("pid", projectId)
                .param("caseId", caseId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** {@code merged} or {@code closed}, as the provider last said. */
    public void markState(String id, String state, Instant now) {
        jdbc.sql("UPDATE fix_pr SET state = :state, updated_at = :now WHERE id = :id")
                .param("state", state)
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    /** A commit Tessary added: the branch's new head and every cause the pull request now addresses. */
    public void recordCommit(String id, String headSha, Collection<String> causes, Instant now) {
        jdbc.sql("UPDATE fix_pr SET head_sha = :head, causes = CAST(:causes AS jsonb), updated_at = :now"
                        + " WHERE id = :id")
                .param("head", headSha)
                .param("causes", json(causes))
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    private String json(Collection<String> causes) {
        return mapper.valueToTree(List.copyOf(causes)).toString();
    }

    private static FixPrRow map(ResultSet rs) throws SQLException {
        return new FixPrRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("case_id"),
                rs.getString("branch"),
                rs.getInt("pr_number"),
                rs.getString("pr_url"),
                rs.getString("state"),
                rs.getString("head_sha"),
                FixRunRepository.strings(rs, "cause_keys"),
                rs.getString("opened_at"),
                rs.getString("updated_at"));
    }
}
