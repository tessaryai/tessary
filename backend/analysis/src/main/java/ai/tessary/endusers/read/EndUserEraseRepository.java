// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Deletes every row of one user key. The caller holds the transaction, so an erasure is whole or not at all. The
 * snapshot and issue rows carry counts only, so they hold nothing of the key.
 */
@Repository
public class EndUserEraseRepository {

    private final JdbcClient jdbc;

    public EndUserEraseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Rows deleted per table. */
    public record Erased(int endUser, int days, int hits, int issueHits, int aliasEdges) {

        public int total() {
            return endUser + days + hits + issueHits + aliasEdges;
        }
    }

    /** Empty when the project never stored a fact: it then holds no user key. */
    public Optional<byte[]> salt(String projectId) {
        return jdbc.sql("SELECT salt FROM end_user_project WHERE project_id = :pid")
                .param("pid", projectId)
                .query((rs, n) -> rs.getBytes("salt"))
                .optional();
    }

    public Erased erase(String projectId, byte[] userKey) {
        return new Erased(
                delete("DELETE FROM end_user WHERE project_id = :pid AND user_key = :key", projectId, userKey),
                delete("DELETE FROM end_user_day WHERE project_id = :pid AND user_key = :key", projectId, userKey),
                delete("DELETE FROM end_user_hit WHERE project_id = :pid AND user_key = :key", projectId, userKey),
                delete(
                        "DELETE FROM end_user_issue_hit WHERE project_id = :pid AND user_key = :key",
                        projectId,
                        userKey),
                delete(
                        "DELETE FROM end_user_alias_edge WHERE project_id = :pid AND (from_key = :key OR to_key = :key)",
                        projectId,
                        userKey));
    }

    private int delete(String sql, String projectId, byte[] userKey) {
        return jdbc.sql(sql).param("pid", projectId).param("key", userKey).update();
    }
}
