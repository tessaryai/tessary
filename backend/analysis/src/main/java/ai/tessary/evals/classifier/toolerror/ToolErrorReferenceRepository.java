// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.toolerror;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The references humans have accepted for a project's tools — the store behind <em>Legitimate — absorb</em>
 * on a tool-error finding. Design contract: {@code classifiers/tool_error/PROGRAM.md} §5 and migration
 * {@code 0050}.
 *
 * <p>Read once per replay and written once per press, which is why it is a whole-project read rather than a
 * per-tool one: the replay already holds every tool's buckets in memory, so asking per tool would turn one
 * query into one per tool for a table that holds a handful of rows.
 */
@Repository
public class ToolErrorReferenceRepository {

    /**
     * One accepted reference.
     *
     * @param calls the call count the human accepted as normal
     * @param failures the failures among them — with {@code calls}, exactly what {@link
     *     ToolErrorDetector#baselineRate} consumes
     * @param acceptedAt when it was accepted. The replay resumes from the bucket after this, so the
     *     evidence behind the absorbed spell is not re-accumulated against the reference that replaced it.
     */
    public record AcceptedReference(String toolKey, long calls, long failures, String acceptedAt) {

        /** The counts as the detector's in-control window. */
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

    /** Every accepted reference in the project, keyed by tool. Empty for a project nobody has corrected. */
    public Map<String, AcceptedReference> byTool(String projectId) {
        return list(projectId).stream().collect(Collectors.toMap(AcceptedReference::toolKey, r -> r));
    }

    /** Every accepted reference in the project, for the map above and for tests that want the order. */
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

    /**
     * Pin {@code toolKey}'s reference to the counts a human accepted, replacing any earlier one.
     *
     * <p>Upsert rather than insert: absorbing twice is a person accepting a rate that moved again, and the
     * newer decision is the one that holds. Keeping both would leave the detector choosing between two
     * references a human stated, which is a choice it has no basis to make.
     */
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
