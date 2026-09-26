// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectDeleteJobRepository;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The background purge through the real {@link ProjectPurgeWorker}: a marked project's data goes, its neighbour's
 * stays. The broken parts were the worker's (table order, its own job row surviving {@code job} being emptied,
 * dropping {@code project} last), so a repository-only test would pass while the worker was wrong.
 */
@SpringBootTest
class ProjectPurgeIntegrationTest {

    @Autowired
    ProjectPurgeWorker worker;

    @Autowired
    ProjectDeleteJobRepository jobs;

    @Autowired
    ProjectRepository projects;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

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
        jdbc.sql("""
                        INSERT INTO job (id, project_id, kind, status, attempts, payload, created_at, updated_at)
                        VALUES (:id, :pid, 'classifier', 'pending', 0, '{}', :now, :now)
                        """)
                .param("id", "sibling-" + p.id())
                .param("pid", p.id())
                .param("now", Instant.now().toString())
                .update();

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

    private void insertTrace(Project p, String id, String parentTraceId, String at) {
        jdbc.sql("INSERT INTO trace (project_id, id, parent_trace_id, started_at, event_ts)"
                        + " VALUES (:pid, :id, :parent, :at::timestamptz, :at::timestamptz)")
                .param("pid", p.id())
                .param("id", id)
                .param("parent", parentTraceId)
                .param("at", at)
                .update();
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
            String traceId = "t-" + p.id() + "-" + i;
            String spanId = "s-" + p.id() + "-" + i;
            String mediaId = "m-" + p.id() + "-" + i;
            jdbc.sql("INSERT INTO trace (project_id, id, started_at, event_ts)"
                            + " VALUES (:pid, :id, :at::timestamptz, :at::timestamptz)")
                    .param("pid", p.id())
                    .param("id", traceId)
                    .param("at", at)
                    .update();
            jdbc.sql("INSERT INTO span (project_id, trace_id, id, kind, started_at, event_ts)"
                            + " VALUES (:pid, :tid, :id, 'llm', :at::timestamptz, :at::timestamptz)")
                    .param("pid", p.id())
                    .param("tid", traceId)
                    .param("id", spanId)
                    .param("at", at)
                    .update();
            jdbc.sql("INSERT INTO span_payload (project_id, trace_id, span_id, event_ts)"
                            + " VALUES (:pid, :tid, :sid, :at::timestamptz)")
                    .param("pid", p.id())
                    .param("tid", traceId)
                    .param("sid", spanId)
                    .param("at", at)
                    .update();
            jdbc.sql("INSERT INTO media_object (id, project_id, digest, media_type, bytes, size_bytes, created_at)"
                            + " VALUES (:id, :pid, :digest, 'image/png', :bytes, 3, :at)")
                    .param("id", mediaId)
                    .param("pid", p.id())
                    .param("digest", "sha256:" + mediaId)
                    .param("bytes", new byte[] {1, 2, 3})
                    .param("at", at)
                    .update();
            jdbc.sql("INSERT INTO media_ref (project_id, media_id, trace_id, span_id)"
                            + " VALUES (:pid, :media, :tid, :sid)")
                    .param("pid", p.id())
                    .param("media", mediaId)
                    .param("tid", traceId)
                    .param("sid", spanId)
                    .update();
            jdbc.sql("INSERT INTO tool_call (id, project_id, created_at, event_ts)"
                            + " VALUES (:id, :pid, :at::timestamptz, :at::timestamptz)")
                    .param("id", "tc-" + p.id() + "-" + i)
                    .param("pid", p.id())
                    .param("at", at)
                    .update();
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
