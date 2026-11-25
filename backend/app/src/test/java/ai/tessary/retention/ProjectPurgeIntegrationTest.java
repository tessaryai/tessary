// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectDeleteJobRepository;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.LogCapture;
import ai.tessary.testsupport.SeedRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The background purge through the real {@link ProjectPurgeWorker}: a marked project's data goes, its neighbour's
 * stays. The broken parts were the worker's (table order, its own job row surviving {@code job} being emptied,
 * dropping {@code project} last), so a repository-only test would pass while the worker was wrong.
 */
@SpringBootTest
record ProjectPurgeIntegrationTest(
        ProjectPurgeWorker worker,
        ProjectDeleteJobRepository jobs,
        ProjectRepository projects,
        TenantService tenants,
        JdbcClient jdbc) {

    @Test
    @DisplayName("a marked project's rows and the project itself are gone; a neighbour project is untouched")
    void purgesOnlyTheMarkedProject() {
        Project doomed = project("purge-doomed");
        Project bystander = project("purge-bystander");
        traffic(doomed, 12);
        traffic(bystander, 5);

        accept(doomed);
        worker.tick();

        assertTrue(projects.findById(doomed.id()).isEmpty(), "project row should be gone");
        assertEquals(0, rows("span", doomed));
        assertEquals(0, rows("span_payload", doomed));
        assertEquals(0, rows("tool_call", doomed));
        assertEquals(0, rows("media_object", doomed));

        assertTrue(projects.findById(bystander.id()).isPresent(), "bystander must survive");
        assertEquals(5, rows("span", bystander));
        assertEquals(5, rows("tool_call", bystander));
        assertEquals(5, rows("media_object", bystander));
    }

    /**
     * {@code job.project_id} cascades, so a purge job naming its own project would delete itself and re-run forever.
     */
    @Test
    @DisplayName("the purge job survives emptying the job table and ends up done")
    void purgeJobSurvivesItsOwnPurge() {
        Project p = project("purge-selfref");
        traffic(p, 3);
        // A sibling job the purge should take with it.
        SeedRows.insert(jdbc, """
            job | id         | project_id | kind       | status  | attempts | payload | created_at | updated_at
                | sibling-$1 | $1         | classifier | pending | 0        | {}      | $2         | $2
            """, p.id(), Instant.now());

        accept(p);
        worker.tick();

        assertEquals(
                0,
                jdbc.sql("SELECT count(*) FROM job WHERE id = :id")
                        .param("id", "sibling-" + p.id())
                        .query(Integer.class)
                        .single(),
                "the project's other jobs should have been purged");
        assertEquals(
                1,
                jdbc.sql("SELECT count(*) FROM job WHERE kind = 'project_delete' AND dedupe_key = :pid"
                                + " AND status = 'done'")
                        .param("pid", p.id())
                        .query(Integer.class)
                        .single(),
                "the purge job itself should survive and be marked done");
    }

    /**
     * A backend that dies between marking and enqueuing leaves an unpurged project; the marker is the durable record,
     * so the worker must find it.
     */
    @Test
    @DisplayName("a project marked with no job is revived by the recovery sweep and purged")
    void revivesAMarkedProjectWithNoJob() {
        Project p = project("purge-orphan");
        traffic(p, 4);
        projects.markDeleting(p.id(), Instant.now().toString()); // marked, never enqueued

        worker.tick();

        assertTrue(projects.findById(p.id()).isEmpty(), "the orphaned mark should have been picked up");
    }

    /**
     * {@code fk_trace_parent} has no cascade or deferral, so deleting by physical position can delete a parent before
     * its child and fail the next batch's FK check.
     */
    @Test
    @DisplayName("a project with parent/child traces purges without violating fk_trace_parent")
    void purgesTraceChainWithoutFkViolation() {
        Project p = project("purge-trace-chain");
        String at = Instant.now().toString();
        String root = "root-" + p.id();
        String child = "child-" + p.id();
        String grandchild = "grandchild-" + p.id();
        insertTrace(p, root, null, at);
        insertTrace(p, child, root, at);
        insertTrace(p, grandchild, child, at);

        accept(p);
        worker.tick();

        assertTrue(projects.findById(p.id()).isEmpty(), "project row should be gone");
        assertEquals(0, rows("trace", p));
    }

    /**
     * {@code end_user_day} and {@code end_user_hit} grow with traffic, so the cascade of the final project delete would
     * remove them in one statement; the other twelve are small and leave by cascade. The {@code deleted} count of the
     * done line shows the two went through batches.
     */
    @Test
    @DisplayName("a project's rows in every end-user table are gone, the two large tables deleted in batches")
    void projectPurge_emptiesEveryEndUserTable() {
        Project p = project("purge-end-users");
        endUserRows(p);
        assertEquals(endUserTableCounts(1), endUserRowCounts(p), "one seeded row in each of the 14 tables");

        LogCapture log = LogCapture.during(ProjectPurgeWorker.class, () -> {
            accept(p);
            worker.tick();
        });

        assertEquals(endUserTableCounts(0), endUserRowCounts(p));
        assertEquals(
                List.of(2L),
                log.fields("project.purge.done").stream()
                        .filter(f -> p.id().equals(f.get("project")))
                        .map(f -> f.get("deleted"))
                        .toList(),
                "the end_user_day and end_user_hit rows should be deleted by the batched purge, not the cascade");
    }

    private static final List<String> END_USER_TABLES = List.of(
            "end_user",
            "end_user_alias_edge",
            "end_user_day",
            "end_user_day_null",
            "end_user_fold_day",
            "end_user_hit",
            "end_user_hit_summary",
            "end_user_issue",
            "end_user_issue_hit",
            "end_user_project",
            "end_user_route",
            "end_user_silence_event",
            "end_user_snapshot",
            "end_user_version");

    private Map<String, Integer> endUserTableCounts(int each) {
        Map<String, Integer> expected = new TreeMap<>();
        END_USER_TABLES.forEach(t -> expected.put(t, each));
        return expected;
    }

    /** Every table in the schema named {@code end_user} or {@code end_user_*}, so a table added later is counted too. */
    private Map<String, Integer> endUserRowCounts(Project p) {
        Map<String, Integer> counts = new TreeMap<>();
        jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                        + " AND (tablename = 'end_user' OR tablename LIKE 'end\\_user\\_%')")
                .query(String.class)
                .list()
                .forEach(t -> counts.put(t, rows(t, p)));
        return counts;
    }

    private void endUserRows(Project p) {
        byte[] salt = new byte[32];
        byte[] userKey = new byte[16];
        byte[] otherKey = new byte[16];
        Arrays.fill(userKey, (byte) 7);
        Arrays.fill(otherKey, (byte) 9);
        String slots = "0".repeat(36) + "1" + "0".repeat(59);
        SeedRows.insert(jdbc, """
            end_user_project       | project_id | salt
                                   | $1         | $2
            end_user_route         | project_id | route_no | trace_name | call_site_id
                                   | $1         | 1        | answer     | __unattributed__
            end_user_day           | project_id | user_key | day   | route_no | version_no | traces | hour_mask | slot_mask
                                   | $1         | $3       | 20781 | 1        | 1          | 1      | 512       | $5
            end_user_day_null      | project_id | day   | route_no | version_no | traces | traces_no_user_id
                                   | $1         | 20781 | 1        | 1          | 1      | 0
            end_user_version       | project_id | version_no | project_version_id | first_trace_day
                                   | $1         | 1          | null               | 20781
            end_user_alias_edge    | project_id | from_key | to_key | via     | day
                                   | $1         | $3       | $4     | session | 20781
            end_user_hit           | project_id | finding_id | role    | user_key | day   | route_no | refs | source
                                   | $1         | f-$1       | witness | $3       | 20781 | 1        | 1    | evidence
            end_user_hit_summary   | project_id | finding_id | refs_total | refs_no_user_id | session_only_refs | complete | hit_rule
                                   | $1         | f-$1       | 1          | 0               | 0                 | true     | witness
            end_user               | project_id | user_key | id_text | shape_signature | first_seen_day | last_seen_day
                                   | $1         | $3       | usr_7Hq2kD | usr:10       | 20781          | 20781
            end_user_silence_event | project_id | scope | start_day | state | members
                                   | $1         | all   | 20770     | open  | 3
            end_user_snapshot      | project_id | day   | logic_version | page_state
                                   | $1         | 20781 | v3            | ok
            end_user_issue         | project_id | lineage_key | day   | user_ids_hit | verdict          | shown
                                   | $1         | lin-$1      | 20781 | 1            | evidence_of_loss | true
            end_user_issue_hit     | project_id | lineage_key | user_key | first_hit_day | in_estimate
                                   | $1         | lin-$1      | $3       | 20781         | true
            end_user_fold_day      | project_id | day   | folded_at | traces
                                   | $1         | 20781 | $6        | 1
            """, p.id(), salt, userKey, otherKey, slots, Instant.now());
    }

    private void insertTrace(Project p, String id, @Nullable String parentTraceId, String at) {
        SeedRows.insert(jdbc, """
            trace | project_id | id | parent_trace_id | started_at | event_ts
                  | $1         | $2 | $3              | $4         | $4
            """, p.id(), id, parentTraceId, at);
    }

    /**
     * A purge past its retry budget un-hides the project ({@code deleting_at} cleared) so a human notices, with its
     * API keys still revoked.
     */
    @Test
    @DisplayName("a purge that exhausts its retry budget un-hides the project")
    void exhaustedPurgeUnhidesTheProject() {
        Project p = project("purge-exhausted");
        accept(p);
        // A purge hung mid-run: claimed, lease expired, attempts past any cap.
        jdbc.sql("""
            UPDATE job SET status = 'claimed', lease_owner = 'stuck-worker',
                           lease_expires_at = :past, attempts = 99
             WHERE kind = 'project_delete' AND dedupe_key = :pid
            """)
                .param("past", Instant.now().minusSeconds(3600).toString())
                .param("pid", p.id())
                .update();

        worker.tick();

        assertFalse(
                projects.findById(p.id()).orElseThrow().isDeleting(),
                "deleting_at should be cleared once the purge is dead-lettered");
        assertTrue(
                projects.findActive().stream().anyMatch(a -> a.id().equals(p.id())),
                "the project should be visible again for someone to investigate");
    }

    /**
     * The old stable job id collided on a second revival, and since one {@code enqueueMissing} statement covers every
     * orphan, one collision blocked all recovery. Pinned: two revivals beside a sibling orphan.
     */
    @Test
    @DisplayName("enqueueMissing survives a project dead-lettering twice, without blocking a sibling revival")
    void enqueueMissingSurvivesRepeatedDeadLetter() {
        Project chronic = project("purge-chronic-deadletter");
        Project sibling = project("purge-sibling-orphan");
        String now = Instant.now().toString();
        projects.markDeleting(chronic.id(), now);
        projects.markDeleting(sibling.id(), now);

        // Scoped to these projects' rows: the sweep covers every marked project, and other tests leave theirs behind.
        jobs.enqueueMissing(now);
        assertEquals(1, purgeJobsAllStatuses(chronic.id()), "chronic project gets its first job");
        assertEquals(1, purgeJobsAllStatuses(sibling.id()), "sibling project gets its first job");
        deadLetter(chronic.id());

        jobs.enqueueMissing(now);
        assertEquals(
                2, purgeJobsAllStatuses(chronic.id()), "the chronic project now has two job rows, not a pkey clash");
        assertEquals(
                1,
                purgeJobsAllStatuses(sibling.id()),
                "the sibling already has a live job and must not be revived again");
        deadLetter(chronic.id());

        // The third revival must not collide or abort the sibling's recovery.
        jobs.enqueueMissing(now);
        assertEquals(3, purgeJobsAllStatuses(chronic.id()), "three rows total, one per revival, none clashed");
        assertEquals(
                1,
                purgeJobsAllStatuses(sibling.id()),
                "sibling job untouched throughout, proving no whole-sweep abort occurred");

        // Never purged, so clean up: with CLAIM_BATCH 1 a stray pending job could win a later test's tick.
        cleanUp(chronic);
        cleanUp(sibling);
    }

    /**
     * Deletes a project's jobs and row, bypassing the worker, so a mid-delete project does not leak into later tests.
     */
    private void cleanUp(Project p) {
        jdbc.sql("DELETE FROM job WHERE kind = 'project_delete' AND dedupe_key = :pid")
                .param("pid", p.id())
                .update();
        jdbc.sql("DELETE FROM project WHERE id = :pid").param("pid", p.id()).update();
    }

    private void deadLetter(String projectId) {
        jdbc.sql("UPDATE job SET status = 'failed' WHERE kind = 'project_delete' AND dedupe_key = :pid"
                        + " AND status IN ('pending', 'claimed')")
                .param("pid", projectId)
                .update();
    }

    private int purgeJobsAllStatuses(String projectId) {
        return jdbc.sql("SELECT count(*) FROM job WHERE kind = 'project_delete' AND dedupe_key = :pid")
                .param("pid", projectId)
                .query(Integer.class)
                .single();
    }

    /** A project marked for deletion is out of every sweep's working set. */
    @Test
    @DisplayName("findActive excludes projects being deleted")
    void deletingProjectsLeaveTheActiveSet() {
        Project p = project("purge-active-set");
        assertTrue(projects.findActive().stream().anyMatch(a -> a.id().equals(p.id())));

        projects.markDeleting(p.id(), Instant.now().toString());

        assertFalse(projects.findActive().stream().anyMatch(a -> a.id().equals(p.id())));
        assertTrue(projects.findById(p.id()).orElseThrow().isDeleting());

        // Undo the mark, or a later test's tick could revive it and win CLAIM_BATCH=1.
        projects.clearDeleting(p.id());
    }

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    /** Mark and enqueue, as the DELETE endpoint does. */
    private void accept(Project p) {
        String now = Instant.now().toString();
        projects.markDeleting(p.id(), now);
        jobs.enqueue(p.id(), now);
    }

    /**
     * {@code n} traces, each with a span, payload, tool call and media object: the volume tables the purge walks. The
     * media object is reachable through a {@code media_ref} row.
     */
    private void traffic(Project p, int n) {
        String at = Instant.now().toString();
        for (int i = 0; i < n; i++) {
            SeedRows.insert(jdbc, """
                trace        | project_id | id       | started_at | event_ts
                             | $1         | t-$2     | $3         | $3
                span         | project_id | trace_id | id         | kind | started_at | event_ts
                             | $1         | t-$2     | s-$2       | llm  | $3         | $3
                span_payload | project_id | trace_id | span_id    | event_ts
                             | $1         | t-$2     | s-$2       | $3
                media_object | id         | project_id | digest      | media_type | bytes | size_bytes | created_at
                             | m-$2       | $1         | sha256:m-$2 | image/png  | $4    | 3          | $3
                media_ref    | project_id | media_id   | trace_id    | span_id
                             | $1         | m-$2       | t-$2        | s-$2
                tool_call    | id         | project_id | created_at  | event_ts
                             | tc-$2      | $1         | $3          | $3
                """, p.id(), p.id() + "-" + i, at, new byte[] {1, 2, 3});
        }
    }

    private int rows(String table, Project p) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE project_id = :pid")
                .param("pid", p.id())
                .query(Integer.class)
                .single();
    }

    private record JobState(String status, int attempts, String lastError, String leaseOwner) {}

    private JobState job(String id) {
        return jdbc.sql("SELECT status, attempts, last_error, lease_owner FROM job WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new JobState(
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getString("lease_owner")))
                .single();
    }

    /**
     * Bugs: dead-lettering before the attempt budget or retrying past it, storing the error unbounded, keeping the
     * lease, or a purge that ran out of batches keeping its attempt count and dead-lettering for being large.
     *
     * <p>Transactional so the scheduled heartbeat never sees this job.
     */
    @Test
    @Transactional
    @DisplayName("a failed purge is retried until its budget is spent; a continued one starts its budget over")
    void failedAndContinuedPurgesReturnToTheQueue() {
        String projectId = "purge-requeue-" + System.nanoTime();
        jobs.enqueue(projectId, Instant.now().toString());
        String id = jdbc.sql("SELECT id FROM job WHERE kind = 'project_delete' AND dedupe_key = :pid")
                .param("pid", projectId)
                .query(String.class)
                .single();
        jdbc.sql("UPDATE job SET status = 'claimed', lease_owner = 'worker-1', attempts = 2 WHERE id = :id")
                .param("id", id)
                .update();

        jobs.markRetryable(id, "x".repeat(2500), 2, 5);
        assertEquals(new JobState("pending", 2, "x".repeat(2000), null), job(id));

        jobs.releaseForContinuation(id);
        assertEquals(new JobState("pending", 0, "x".repeat(2000), null), job(id));

        jobs.markRetryable(id, "boom", 5, 5);
        assertEquals(new JobState("failed", 0, "boom", null), job(id));
    }
}
