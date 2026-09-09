// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import ai.tessary.detection.DetectionTableRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The bounded deletes retention is made of, one per {@code retention_policy} data class.
 *
 * <p>Every statement is {@code ctid IN (SELECT … LIMIT n)} rather than a bare predicate. That is what
 * makes the work bounded per call: the planner deletes at most {@code n} physical rows and the caller
 * loops, so a project with a year of backlog drains over many short statements instead of one that
 * holds row locks over a table scan.
 *
 * <p>Each returns the number of rows the statement actually removed, which is both the caller's
 * "is there more to do" signal and the number the sweep reports.
 *
 * <h2>Payloads age out first</h2>
 *
 * <p>The traces class is three statements, in order. {@code span_payload} is most of the bytes and the
 * least of the reads, so it is purged on its own shorter horizon while the {@code span} and
 * {@code trace} rows survive: a trace whose payload has expired stays fully functional on every list,
 * rollup, vitals and metric surface, and only the detail view loses its text, which the frontend
 * renders as an explicit "payload expired" state rather than an empty prompt. Deleting the whole trace
 * to reclaim the same bytes would take the numbers with it. The third statement collects the
 * {@code finding_evidence} rows whose substrate has since gone.
 *
 * <h2>The pin</h2>
 *
 * <p>Substrate a live claim is based on is never deleted, whatever its age. {@link #PINNED} is that
 * rule, written once and spliced into every statement that can remove substrate, because a pin one
 * tier honours and another does not is not a pin: ageing a payload out from under a pinned trace
 * leaves the case page pointing at a trace with no text, the same broken evidence link by a slower
 * route.
 *
 * <p>It has two legs and needs both. The finding leg holds while the claim is live
 * ({@code status IN ('open','blocked')}); the case leg holds while a human still has the case in front
 * of them ({@code state <> 'resolved'}, muted included: muted is "not now", not "done with"). Neither
 * subsumes the other: a finding can be resolved under a case someone is still reading, and a finding
 * can be live before any case has been opened on it.
 *
 * <p>It reads at both grains evidence can carry. A span-grain row pins its parent trace through the
 * denormalized {@code trace_id} the grain check requires alongside every span id, so no statement here
 * ever joins {@code span} to find out what a span belongs to. A session-grain row pins every trace of
 * that session: the claim is about the conversation, and a session whose turns have been deleted
 * underneath it is evidence of nothing. The {@code =} comparisons are null-safe by SQL's own rules: a
 * trace with no session never matches a session-grain row, so no {@code IS NOT NULL} guard is needed.
 */
@Repository
public class RetentionRepository {

    /**
     * The pin, as a fragment: {@code EXISTS (…)} over the evidence of a live finding or an unresolved
     * case, for a row exposing a trace id and a session id under the columns substituted in.
     *
     * <p>Both tokens take a column reference, never a value: {@link #withPin} is the only substituter
     * and every argument it is given is a literal in this file. Nothing here interpolates a parameter;
     * the two the statements take are still bound by name.
     */
    private static final String PINNED = """
            EXISTS (
                SELECT 1 FROM finding_evidence e
                WHERE e.project_id = :pid
                  AND (e.trace_id = {trace} OR e.session_id = {session})
                  AND (EXISTS (SELECT 1 FROM finding f
                                WHERE f.id = e.finding_id AND f.status IN ('open', 'blocked'))
                    OR EXISTS (SELECT 1 FROM eval_case c
                                WHERE c.finding_id = e.finding_id AND c.state <> 'resolved')))""";

    /** Splice {@link #PINNED} into a statement's {@code {pin}} slot, reading it off the named columns. */
    private static String withPin(String sql, String traceId, String sessionId) {
        return sql.replace("{pin}", PINNED.replace("{trace}", traceId).replace("{session}", sessionId));
    }

    private final JdbcClient jdbc;
    private final DetectionTableRegistry detectionTables;

    public RetentionRepository(JdbcClient jdbc, DetectionTableRegistry detectionTables) {
        this.jdbc = jdbc;
        this.detectionTables = detectionTables;
    }

    /**
     * Delete aged span payloads: the first tier, and the one that reclaims almost all of the bytes.
     *
     * <p>Keyed off the span rather than the payload's own {@code event_ts} so the two tiers cut on
     * exactly the same clock, and bounded by {@code ctid} like everything else here. A trace whose
     * payloads this removes keeps every column any list, rollup or detector reads.
     *
     * <p>The pin applies here, at full strength. A case renders a trace's text, so purging the payload
     * of a trace a live claim points at empties the evidence page without deleting the link to it: the
     * product keeping its promise about the pointer and breaking it about the content. The session is
     * taken from the {@code trace} row rather than the span's denormalized copy, and taken through a
     * left join: an uncorrelated span carries {@code session_id} null until the correlation resolver
     * reaches it, and reading the pin off that column would age out the payload of a span whose trace
     * is pinned by the session leg. A payload whose trace row is missing entirely pins on nothing and
     * ages normally.
     */
    public int deleteSpanPayloads(String projectId, String cutoff, int limit) {
        return jdbc.sql(withPin("""
                        DELETE FROM span_payload WHERE ctid IN (
                            SELECT p.ctid FROM span_payload p
                            JOIN span s ON s.project_id = p.project_id
                                       AND s.trace_id = p.trace_id AND s.id = p.span_id
                            LEFT JOIN trace t ON t.project_id = p.project_id AND t.id = p.trace_id
                            WHERE p.project_id = :pid
                              AND s.started_at < :cutoff::timestamptz
                              AND NOT {pin}
                            LIMIT :n)
                        """, "s.trace_id", "t.session_id"))
                .param("pid", projectId)
                .param("cutoff", cutoff)
                .param("n", limit)
                .update();
    }

    /**
     * Delete aged traces. Everything hanging off a trace, its spans and their payloads, goes with it
     * through {@code ON DELETE CASCADE}, so this one statement is the whole structural tier.
     *
     * <p>The key range is the producer key: cutting on {@code trace.started_at} rather than an ingest
     * clock is what makes a replayed corpus age on the timeline it actually happened on, which is what
     * a retention commitment says.
     *
     * <p>A trace a live finding or an unresolved case points at is never deleted. A case renders its
     * before/after exemplars by id, so aging one out turns a live case into a page of dead links, the
     * product telling someone to look at evidence it threw away.
     *
     * <p>It covers the whole evidence set rather than one column, since a rate shift's witnesses can
     * span several {@code finding_evidence} rows and the pin has to read all of them, at every grain.
     *
     * <p>See {@link #PINNED} for the two legs and the two grains.
     */
    public int deleteTraces(String projectId, String cutoff, int limit) {
        return jdbc.sql(withPin("""
                        DELETE FROM trace WHERE ctid IN (
                            SELECT t.ctid FROM trace t
                            WHERE t.project_id = :pid
                              AND t.started_at < :cutoff::timestamptz
                              AND NOT {pin}
                            LIMIT :n)
                        """, "t.id", "t.session_id"))
                .param("pid", projectId)
                .param("cutoff", cutoff)
                .param("n", limit)
                .update();
    }

    /**
     * Delete evidence rows that point at substrate which is gone: the pin's release, and the reason it
     * is a pin rather than a leak.
     *
     * <p>The predicate is the whole guard, in both directions. A row is collected only once its finding
     * has left {@code open}/{@code blocked} and no unresolved case stands on that finding and the row's
     * own substrate has already been deleted, which, by the statements above, can only have happened
     * while both of those were already true. So this never removes a reference that still resolves, and
     * never removes one a live claim depends on; it removes the dangling remainder of a closed claim.
     *
     * <p>Deliberately not a FK cascade. A NO ACTION FK into {@code trace} would make retention fail
     * rather than skip, and a CASCADE would silently rewrite a closed finding's evidence set the moment
     * its traces aged, shrinking the count of what a finding was based on under a reader's feet.
     * Collecting the rows here, after the fact and only when the claim is closed, keeps that count
     * honest for as long as anyone can act on it.
     *
     * <p>The finding itself is untouched. Its {@code payload} holds the magnitudes, so a closed finding
     * whose substrate has aged out still says what it found; it just can no longer show you the turns.
     *
     * <p>A session-grain row releases on its traces, not on the {@code session} row. Nothing in
     * retention deletes sessions (a session is a handful of columns and no statement here touches the
     * table), so a release conditioned on the session row disappearing would never happen, and the
     * session leg of the pin would hold its evidence forever while looking exactly like the trace leg.
     * What the claim was about is the conversation's turns, so the row becomes collectable when the
     * last of them has aged out. Both {@code NOT EXISTS}es are null-safe against the other grain: a
     * trace-grain row's null {@code session_id} matches no trace, and a session-grain row's null
     * {@code trace_id} matches no trace either, so each row is judged only by its own grain.
     */
    public int deleteOrphanedEvidence(String projectId, int limit) {
        return jdbc.sql(withPin("""
                        DELETE FROM finding_evidence WHERE ctid IN (
                            SELECT e2.ctid FROM finding_evidence e2
                            WHERE e2.project_id = :pid
                              AND NOT {pin}
                              AND NOT EXISTS (
                                  SELECT 1 FROM trace t
                                  WHERE t.project_id = e2.project_id AND t.id = e2.trace_id)
                              AND NOT EXISTS (
                                  SELECT 1 FROM trace ts
                                  WHERE ts.project_id = e2.project_id AND ts.session_id = e2.session_id)
                            LIMIT :n)
                        """, "e2.trace_id", "e2.session_id"))
                .param("pid", projectId)
                .param("n", limit)
                .update();
    }

    /**
     * How many trace rows the pin is currently holding open, for the sweep to report.
     *
     * <p>An unbounded pin is indistinguishable from a working retention policy in every other number the
     * sweep prints: deletions simply stop, quietly, and the project's disk stops falling. This is the
     * count that makes it visible: if it climbs without a matching climb in open findings, some
     * classifier is writing evidence it never closes.
     */
    public long countPinnedTraces(String projectId) {
        // count(*) is never NULL and `single()` throws on an empty result, so there is nothing to
        // defend against here: a null check on this would be dead code the analyzer rejects.
        return jdbc.sql(withPin("""
                        SELECT count(*) FROM trace t
                        WHERE t.project_id = :pid AND {pin}
                        """, "t.id", "t.session_id"))
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /**
     * One detection table's bounded delete, with the table name as the only substitution. {@code {table}}
     * takes a name from {@link ai.tessary.detection.DetectionTableRegistry#tables()} and nothing else; the two parameters are still bound.
     */
    private static final String DELETE_DETECTIONS = """
            DELETE FROM {table} WHERE ctid IN (
                SELECT d.ctid FROM {table} d
                WHERE d.project_id = :pid
                  AND d.created_at < :cutoff::timestamptz
                  AND NOT {pin}
                LIMIT :n)
            """;

    /**
     * Delete aged classifier detections: the Layer-1 output, at ingest rate and by far the highest row
     * count any classifier produces.
     *
     * <p>The pin applies, at the session and trace grains a detection carries. A detection is what a
     * finding's evidence points at, so aging one out from under a live claim empties the page that
     * explains the claim. It is read off the detection's own denormalized subject columns rather than
     * by joining {@code span}, exactly as the trace tier does.
     *
     * <p>The {@code limit} is a budget for the whole call, spent across the registered tables in
     * order, not a limit per table: the total this returns is {@code <= limit}, which is what
     * {@link RetentionSweeper} needs, since it decides "was there more to do" by testing
     * {@code removed < batchSize}. A table that exhausts the budget leaves the rest to the next batch,
     * which the sweeper takes because the budget was spent in full.
     */
    public int deleteDetections(String projectId, String cutoff, int limit) {
        int deleted = 0;
        // Every registered DetectionTable, sorted by table name (DetectionTableRegistry#tables()),
        // so the budget is spent in a deterministic order across calls rather than incidental bean
        // order.
        for (var table : detectionTables.tables()) {
            int remaining = limit - deleted;
            if (remaining <= 0) break;
            deleted += jdbc.sql(withPin(
                            DELETE_DETECTIONS.replace("{table}", table.table()),
                            "d.subject_trace_id",
                            "d.subject_session_id"))
                    .param("pid", projectId)
                    .param("cutoff", cutoff)
                    .param("n", remaining)
                    .update();
        }
        return deleted;
    }

    /**
     * Collect media nothing references any more: the only statement here that is not a policy.
     *
     * <p>An externalized image is referenced by an {@code image_ref} string inside a span payload and
     * by nothing the database can see; {@code media_ref} is that reference made visible, and it
     * cascades with the payload. So the moment a payload ages out, or a batch is dropped after its
     * bytes were stored, the image is unreachable, unnameable and unrenderable, whatever the
     * project's TTL says. Deleting a row nothing can reach is not a retention decision, which is why
     * {@link RetentionSweeper} runs this for every project rather than only for the ones with a
     * bounded traces policy.
     *
     * <p>The cutoff is a grace window, not a TTL. Bytes are stored during validation, before the
     * transaction that writes their {@code media_ref} rows, so a freshly stored image is legitimately
     * unreferenced for the length of a batch. Collecting on age alone would delete an in-flight batch's
     * images out from under it. Anything older than the window and still unreferenced is genuine garbage.
     *
     * <p>Deduplication is respected for free: one row shared by ten spans has ten referrers, and the
     * anti-join collects it when the last of them is gone.
     */
    public int deleteOrphanedMedia(String projectId, String graceCutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM media_object WHERE ctid IN (
                            SELECT m.ctid FROM media_object m
                            WHERE m.project_id = :pid
                              AND m.created_at::timestamptz < :cutoff::timestamptz
                              AND NOT EXISTS (
                                  SELECT 1 FROM media_ref r
                                  WHERE r.project_id = m.project_id AND r.media_id = m.id)
                            LIMIT :n)
                        """)
                .param("pid", projectId)
                .param("cutoff", graceCutoff)
                .param("n", limit)
                .update();
    }
}
