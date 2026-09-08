// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.ProjectDeleteJobRepository;
import ai.tessary.evals.tenant.ProjectRepository;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The background purge, end to end: a marked project's data goes, its neighbour's does not, and the
 * paths that used to make a delete unsurvivable are each pinned by a test.
 *
 * <p>These run the real {@link ProjectPurgeWorker} rather than the repository alone, because the parts
 * that were actually broken are the worker's: the order it empties tables in, the fact that its own job
 * row survives emptying {@code job}, and that the {@code project} row is dropped only after the volume
 * is gone. A test that called {@code deleteBatch} in its own order would pass while the worker used a
 * different one.
 */
@SpringBootTest
class ProjectPurgeIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
     * The bug this whole change exists to prevent, in miniature. {@code job.project_id} cascades to
     * {@code project}, so a purge job that named its own project would be deleted by its own final
     * statement and could never be marked done — the queue would then re-run it forever against a
     * project that no longer exists.
     */
    @Test
    @DisplayName("the purge job survives emptying the job table and ends up done")
    void purgeJobSurvivesItsOwnPurge() {
        Project p = project("purge-selfref");
        traffic(p, 3);
        // A sibling job for the same project — the rows the purge is supposed to take with it.
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
     * A backend that dies between marking and enqueuing leaves a project nobody is purging. The marker,
     * not the job row, is the durable record that a delete was accepted — so the worker must find it.
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

    @Test
    @DisplayName("marking is one-way: a second mark is refused so a retried DELETE stays idempotent")
    void markingIsOneWay() {
        Project p = project("purge-idempotent");
        assertTrue(projects.markDeleting(p.id(), Instant.now().toString()));
        assertFalse(projects.markDeleting(p.id(), Instant.now().toString()));
        // This project is deliberately never enqueued or purged — undo the mark so it doesn't sit as an
        // orphan for a LATER test's worker.tick() to revive and win CLAIM_BATCH=1 over that test's own job.
        projects.clearDeleting(p.id());
    }

    /**
     * {@code trace} has a self-referencing FK ({@code fk_trace_parent}) with no cascade and no
     * deferral. A batch that deletes rows by physical position rather than parent/child order can delete
     * a parent while a child pointing at it via {@code parent_trace_id} survives to a later batch, and
     * that later batch's DELETE then fails the FK check outright. This pins the fix: a project whose
     * traces form a parent/child chain purges cleanly.
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
     * A purge that hangs or crashes past its retry budget must not leave the project stuck forever
     * showing "deleting" — the worker should un-hide it (clear {@code deleting_at}) so a human notices,
     * while its already-revoked API keys stay revoked (see {@link ProjectRepository#clearDeleting}).
     */
    @Test
    @DisplayName("a purge that exhausts its retry budget un-hides the project")
    void exhaustedPurgeUnhidesTheProject() {
        Project p = project("purge-exhausted");
        accept(p);
        // Simulate a purge that hung mid-run past the attempt cap: claimed, lease long expired, attempts
        // past any reasonable cap.
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
     * A stable, project-derived job id (the old {@code 'pdj_' || md5(project_id)}) gets reused every time
     * {@code enqueueMissing} revives the same project, so a purge that dead-letters twice hits its own old
     * {@code job_pkey} on the second revival — and since one {@code enqueueMissing} call covers every
     * orphaned project in a single statement, that single collision would abort recovery for every other
     * stuck project too. This pins that a project can dead-letter and be revived twice, alongside a second
     * orphaned project, with no id collision.
     */
    @Test
    @DisplayName("enqueueMissing survives a project dead-lettering twice, without blocking a sibling revival")
    void enqueueMissingSurvivesRepeatedDeadLetter() {
        Project chronic = project("purge-chronic-deadletter");
        Project sibling = project("purge-sibling-orphan");
        String now = Instant.now().toString();
        projects.markDeleting(chronic.id(), now);
        projects.markDeleting(sibling.id(), now);

        // Assertions are scoped to these two projects' own job rows rather than enqueueMissing's global
        // return count: the sweep operates over every deleting_at project in the database, and other
        // tests in this class leave their own marked projects behind, which would make a global count
        // flaky.
        jobs.enqueueMissing(now);
        assertEquals(1, purgeJobsAllStatuses(chronic.id()), "chronic project gets its first job");
        assertEquals(1, purgeJobsAllStatuses(sibling.id()), "sibling project gets its first job");
        deadLetter(chronic.id());

        // Second revival of the same project, in the same call as an untouched sibling orphan.
        jobs.enqueueMissing(now);
        assertEquals(
                2, purgeJobsAllStatuses(chronic.id()), "the chronic project now has two job rows, not a pkey clash");
        assertEquals(
                1,
                purgeJobsAllStatuses(sibling.id()),
                "the sibling already has a live job and must not be revived again");
        deadLetter(chronic.id());

        // Third revival must not collide with its own second row, and must not abort sibling recovery —
        // the failure mode of the old stable-id approach, where one row's pkey collision aborted the
        // whole bulk statement and silently blocked every other orphaned project's recovery too.
        jobs.enqueueMissing(now);
        assertEquals(3, purgeJobsAllStatuses(chronic.id()), "three rows total, one per revival, none clashed");
        assertEquals(
                1,
                purgeJobsAllStatuses(sibling.id()),
                "sibling job untouched throughout, proving no whole-sweep abort occurred");

        // Neither project was ever actually purged (this test only exercises enqueueMissing, never the
        // worker's claim loop), so both are left marked deleting_at with a live pending job. Clean up
        // explicitly rather than leaving that behind: CLAIM_BATCH is 1, so a stray pending project_delete
        // job would otherwise be free to win a LATER test's worker.tick() call over that test's own job.
        cleanUp(chronic);
        cleanUp(sibling);
    }

    /** Deletes a project's job rows and the project row itself, bypassing the worker — for tests that
     *  deliberately leave a project mid-delete-without-purging and must not leak it into later tests. */
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
        assertTrue(projects.findDeleting().stream().anyMatch(a -> a.id().equals(p.id())));

        // This project is deliberately never enqueued or purged — undo the mark so it doesn't sit as an
        // orphan for a LATER test's worker.tick() to revive and win CLAIM_BATCH=1 over that test's own job.
        projects.clearDeleting(p.id());
    }

    // ---- fixtures ----

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    /** Mark + enqueue, i.e. what the DELETE endpoint does, without going through HTTP. */
    private void accept(Project p) {
        String now = Instant.now().toString();
        projects.markDeleting(p.id(), now);
        jobs.enqueue(p.id(), now);
    }

    /**
     * {@code n} traces, each with a span, that span's payload, a tool call and a media object — the
     * volume tables the purge walks. The media object is referenced from its span_payload via a
     * {@code media_ref} row, the join table {@code 0001-media-ref-and-error-message.sql} (#761/#762)
     * introduced in place of the old unindexed {@code tool_call.arguments_ref}/{@code result_ref}
     * columns — this is what makes {@code media_object} reachable at all today.
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
}
