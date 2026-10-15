// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Project-scoped lineage lookups; a cross-tenant id never resolves. {@code project_version_id} is denormalized onto
 * {@code trace} and {@code span}, so each lookup is one row.
 */
@Repository
public class CommitLineageRepository {

    private final JdbcClient jdbc;

    public CommitLineageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code session} has no stamp of its own because a resumed session can span deploys; {@code MAX} over its traces
     * is the honest answer. Empty when every trace predates stamping.
     */
    public Optional<String> sessionVersionId(String projectId, String sessionId) {
        return jdbc.sql("""
            SELECT MAX(project_version_id) FROM trace
            WHERE project_id = :pid AND session_id = :id AND project_version_id IS NOT NULL
            """)
                .param("pid", projectId)
                .param("id", sessionId)
                .query(String.class)
                .optional();
    }

    /** The turn is the trace; kept for callers holding a legacy turn pointer. */
    public Optional<String> turnVersionId(String projectId, String turnId) {
        return traceVersionId(projectId, turnId);
    }

    public Optional<String> traceVersionId(String projectId, String traceId) {
        return jdbc.sql("""
            SELECT project_version_id FROM trace WHERE project_id = :pid AND id = :id
            """)
                .param("pid", projectId)
                .param("id", traceId)
                .query(String.class)
                .optional();
    }
}
