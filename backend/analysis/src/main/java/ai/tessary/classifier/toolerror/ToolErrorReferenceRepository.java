// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * References humans accepted via <em>Legitimate, absorb</em> on a tool-error finding ({@code tool-error.md} §5). Read
 * whole-project once per replay, since the replay already holds every tool in memory.
 */
@Repository
public class ToolErrorReferenceRepository {

    /** @param acceptedAt the replay resumes from the bucket after this, so the absorbed spell is not re-accumulated */
    public record AcceptedReference(String toolKey, long calls, long failures, String acceptedAt) {

        public ToolErrorRate asRate() {
            ToolErrorRate rate = new ToolErrorRate();
            rate.addCounts(calls, failures);
            return rate;
        }
    }

    private final JdbcClient jdbc;

    public ToolErrorReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int deleteAll(String projectId) {
        return jdbc.sql("DELETE FROM tool_error_reference WHERE project_id = :pid")
                .param("pid", projectId)
                .update();
    }

    public Map<String, AcceptedReference> byTool(String projectId) {
        return list(projectId).stream().collect(Collectors.toMap(AcceptedReference::toolKey, r -> r));
    }

    public List<AcceptedReference> list(String projectId) {
        return jdbc.sql("SELECT tool_key, calls, failures, accepted_at FROM tool_error_reference"
                        + " WHERE project_id = :pid ORDER BY tool_key")
                .param("pid", projectId)
                .query((rs, n) -> new AcceptedReference(
                        rs.getString("tool_key"),
                        rs.getLong("calls"),
                        rs.getLong("failures"),
                        rs.getString("accepted_at")))
                .list();
    }

    /** Upsert: absorbing twice means the newer decision holds, and the detector has no basis to choose between two. */
    public void pin(
            String projectId,
            String toolKey,
            long calls,
            long failures,
            @Nullable String acceptedBy,
            String acceptedAt) {
        jdbc.sql("""
                INSERT INTO tool_error_reference (project_id, tool_key, calls, failures, accepted_by, accepted_at)
                VALUES (:pid, :tool, :calls, :failures, :by, :at)
                ON CONFLICT (project_id, tool_key) DO UPDATE SET
                    calls = EXCLUDED.calls,
                    failures = EXCLUDED.failures,
                    accepted_by = EXCLUDED.accepted_by,
                    accepted_at = EXCLUDED.accepted_at
                """)
                .param("pid", projectId)
                .param("tool", toolKey)
                .param("calls", calls)
                .param("failures", failures)
                .param("by", acceptedBy)
                .param("at", acceptedAt)
                .update();
    }
}
