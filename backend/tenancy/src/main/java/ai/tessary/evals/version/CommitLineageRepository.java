// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.version;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only lineage lookups for {@link CommitLineageService}: each query maps one node id to the
 * {@code project_version.id} it carries. All queries are project-scoped; a cross-tenant id never
 * resolves.
 *
 * <p><b>Only the substrate spine is left, and that is the finished state.</b> Track A removed the
 * four non-substrate node kinds this file used to serve — {@code verdict}, {@code risk_stat},
 * {@code diff_classification} and {@code observer_alert} — along with the raw-SHA provenance shape,
 * which only {@code observer_alert.project_version_sha} ever used. What remains is the direct-FK
 * shape on {@code trace} and {@code span}, plus the session's derived MAX.
 *
 * <p>This sits beside {@code ProjectVersionRepository} in the version slice on purpose: lineage is
 * a read concern of the version spine, and cross-table read joins are the established pattern for
 * that slice.
 *
 * <h2>Every substrate lookup is one row now</h2>
 *
 * <p>The three shapes here used to be a two- and three-deep climb up the context spine,
 * because only the ltree-root session context carried {@code project_version_id}. In v2 the column is
 * denormalized onto {@code trace} and {@code span} at ingest, so each lookup reads the row it was handed.
 *
 * <p>A SESSION has no version of its own (spec §5.2: session is identity only). Its version is
 * {@code MAX(project_version_id)} over its traces — the newest deploy any turn of the session ran under,
 * which is the honest answer for an entity that can span deploys.
 */
@Repository
public class CommitLineageRepository {

    private final JdbcClient jdbc;

    public CommitLineageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A session's version: the newest deploy any of its traces ran under.
     *
     * <p>{@code session} carries no {@code project_version_id} of its own, deliberately — a session can
     * be resumed days later and span several deploys, so a single stamp on it would be a claim the row
     * cannot support. {@code MAX} over its traces is the honest answer, and this is the read side of
     * it. Empty for a session whose traces all predate version stamping, which is honest rather than
     * a failure.
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

    /**
     * A turn's version. The turn IS the trace in v2 (spec §2), so this is {@link #traceVersionId} under
     * the name callers holding a legacy turn pointer still use; both resolve the same row.
     */
    public Optional<String> turnVersionId(String projectId, String turnId) {
        return traceVersionId(projectId, turnId);
    }

    /** {@code trace.project_version_id} — denormalized at ingest, so a single-row read. */
    public Optional<String> traceVersionId(String projectId, String traceId) {
        return jdbc.sql("""
            SELECT project_version_id FROM trace WHERE project_id = :pid AND id = :id
            """)
                .param("pid", projectId)
                .param("id", traceId)
                .query(String.class)
                .optional();
    }

    /**
     * {@code span.project_version_id}, addressed by the producer PAIR.
     *
     * <p>{@code traceId} is required and not an optional narrowing: a producer span id is unique only
     * within its trace, so a bare-id lookup would resolve to whichever trace happened to reuse it.
     */
    public Optional<String> spanVersionId(String projectId, String traceId, String spanId) {
        return jdbc.sql("""
            SELECT project_version_id FROM span
            WHERE project_id = :pid AND trace_id = :tid AND id = :id
            """)
                .param("pid", projectId)
                .param("tid", traceId)
                .param("id", spanId)
                .query(String.class)
                .optional();
    }
}
