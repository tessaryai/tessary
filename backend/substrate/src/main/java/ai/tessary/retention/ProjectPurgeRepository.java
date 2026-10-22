// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement is {@code ctid IN (SELECT … LIMIT n)} so no single statement holds row locks over a table scan.
 * Emptying {@code span_payload} first cascades away {@code media_ref}, so the {@code media_object} pass finds nothing
 * referencing it; the order is kept in case an unindexed FK into {@code media_object} reappears.
 */
@Repository
public class ProjectPurgeRepository {

    /**
     * Everything else is left to the {@code ON DELETE CASCADE} of the final project delete. {@code job} is safe to
     * empty: the purge job's own row has a NULL {@code project_id}.
     */
    public static final List<String> TABLES = List.of(
            "span_payload",
            "tool_call",
            "retrieved_doc",
            "span",
            "trace",
            "media_object",
            "agent_run_step",
            "agent_run",
            "job");

    private final JdbcClient jdbc;

    public ProjectPurgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The table name is interpolated because it cannot be a bind parameter; only {@link #TABLES} literals reach it. */
    public int deleteBatch(String table, String projectId, int limit) {
        if ("trace".equals(table)) {
            return deleteTraceLeafBatch(projectId, limit);
        }
        if ("agent_run_step".equals(table)) {
            return deleteAgentRunStepBatch(projectId, limit);
        }
        return jdbc.sql("DELETE FROM " + table + " WHERE ctid IN (" + "SELECT ctid FROM " + table
                        + " WHERE project_id = :pid LIMIT :n)")
                .param("pid", projectId)
                .param("n", limit)
                .update();
    }

    /**
     * {@code fk_trace_parent} is a non-deferred self-reference, so a positional batch could delete a parent before its
     * child. Deleting only current leaves empties the project without violating it; the caller's loop sees each newly
     * exposed set of leaves.
     */
    public int deleteTraceLeafBatch(String projectId, int limit) {
        return jdbc.sql("""
                DELETE FROM trace
                 WHERE ctid IN (
                     SELECT t.ctid
                       FROM trace t
                      WHERE t.project_id = :pid
                        AND NOT EXISTS (
                            SELECT 1 FROM trace c
                             WHERE c.project_id = t.project_id AND c.parent_trace_id = t.id
                        )
                      LIMIT :n
                 )
                """).param("pid", projectId).param("n", limit).update();
    }

    /** {@code agent_run_step} carries no {@code project_id}; its run does. */
    private int deleteAgentRunStepBatch(String projectId, int limit) {
        return jdbc.sql("""
                DELETE FROM agent_run_step
                 WHERE ctid IN (
                     SELECT s.ctid
                       FROM agent_run_step s JOIN agent_run r ON r.id = s.run_id
                      WHERE r.project_id = :pid
                      LIMIT :n
                 )
                """).param("pid", projectId).param("n", limit).update();
    }
}
