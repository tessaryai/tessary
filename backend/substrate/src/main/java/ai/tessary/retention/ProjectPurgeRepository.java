// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The bounded deletes a project purge is made of — the volume tables, emptied ahead of the cascade.
 *
 * <p>Same shape as {@link RetentionRepository}: every statement is {@code ctid IN (SELECT … LIMIT n)}
 * rather than a bare predicate, so the caller loops and no single statement holds row locks over a table
 * scan. Unlike retention there is no cutoff and no pin — a project being purged has already been accepted
 * for deletion, so every row of it goes.
 *
 * <h2>The order is the point</h2>
 *
 * <p>{@link #TABLES} is not sorted by size, it is sorted by what each pass saves the next one.
 * {@code media_object}'s only referrer today is {@code media_ref} ({@code fk_media_ref_media},
 * {@code ON DELETE CASCADE}, indexed by {@code idx_media_ref_media_id} — see
 * {@code 0001-media-ref-and-error-message.sql}, #761/#762), and {@code media_ref} is itself reached from
 * {@code span_payload} ({@code fk_media_ref_payload}, also {@code ON DELETE CASCADE}). Emptying
 * {@code span_payload} first therefore cascades away every {@code media_ref} row before {@code
 * media_object} is purged directly, so that pass finds nothing left pointing at it.
 *
 * <p>This table used to also carry four dangling FKs from {@code tool_call}/{@code retrieved_doc}
 * declared {@code ON DELETE SET NULL} with no index on the referencing side — the ~42-minute delete this
 * whole change exists to fix. {@code 0001-drop-dead-media-ref-columns} (#761/#762, landed ahead of this
 * migration) removed those four columns entirely in favor of the indexed {@code media_ref} join table
 * above, so that specific cost is already gone by the time a purge runs; the ordering here is what is
 * left of the original fix, kept because it costs nothing and still holds if a future FK into
 * {@code media_object} shows up unindexed.
 */
@Repository
public class ProjectPurgeRepository {

    /**
     * The volume tables, in purge order. Everything not named here is left to the {@code ON DELETE
     * CASCADE} fan-out of the final {@code DELETE FROM project} — roughly forty tables which, on the
     * corpus this was built against, hold four-figure row counts between them and drop in milliseconds.
     *
     * <p>{@code job} is in the list and is safe to empty: the purge job driving this carries a NULL
     * {@code project_id} (see {@code ProjectDeleteJobRepository}), so it is not among the rows this
     * deletes and survives to be marked done.
     */
    public static final List<String> TABLES =
            List.of("span_payload", "tool_call", "retrieved_doc", "span", "trace", "media_object", "job");

    private final JdbcClient jdbc;

    public ProjectPurgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Delete at most {@code limit} rows of one table for one project, returning how many went.
     *
     * <p>The table name is interpolated because a table name cannot be a bind parameter. It is never a
     * caller's string: the only values reaching it are the literals in {@link #TABLES}, and
     * {@link #assertKnown} refuses anything else rather than trusting that to stay true. The project id
     * is bound normally.
     *
     * <p>{@code trace} is special-cased to {@link #deleteTraceLeafBatch} — see its javadoc for why a bare
     * {@code ctid IN (... LIMIT n)} is unsafe for that one table.
     */
    public int deleteBatch(String table, String projectId, int limit) {
        assertKnown(table);
        if ("trace".equals(table)) {
            return deleteTraceLeafBatch(projectId, limit);
        }
        return jdbc.sql("DELETE FROM " + table + " WHERE ctid IN (" + "SELECT ctid FROM " + table
                        + " WHERE project_id = :pid LIMIT :n)")
                .param("pid", projectId)
                .param("n", limit)
                .update();
    }

    /**
     * Delete at most {@code limit} <em>leaf</em> traces of one project — traces not named as any
     * remaining trace's {@code parent_trace_id} — returning how many went.
     *
     * <p>{@code trace} has a self-referencing FK, {@code fk_trace_parent (project_id, parent_trace_id)
     * REFERENCES trace(project_id, id)}, {@code NO ACTION} with no deferral. A plain {@code ctid IN (...
     * LIMIT n)} batch picks rows by physical position, not by parent/child order, so once a project has
     * more traces than one batch it is only a matter of layout before a batch deletes a parent while a
     * child pointing at it via {@code parent_trace_id} survives to a later batch — and that batch's own
     * DELETE then fails the FK check immediately, since the referencing row still exists once its parent
     * is gone.
     *
     * <p>The fix is to always delete from the leaves inward: a trace with nothing left pointing at it as
     * a parent is safe to remove regardless of whether it is a root or a child, so repeatedly deleting
     * "currently childless" traces empties the whole project without ever violating the FK. The caller's
     * existing do-while-full-batch loop (see {@link ai.tessary.retention.ProjectPurgeWorker
     * ProjectPurgeWorker#purgeProject}) already re-invokes this per batch, so each call simply sees the
     * new leaves the previous call's deletes exposed.
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

    private static void assertKnown(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("not a purge table: " + table);
        }
    }
}
