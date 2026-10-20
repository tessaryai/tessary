// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.RcaParkedSpringBootTest;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The RCA queue's lease bookkeeping against the real {@code job} table. The case page reads a claimed job with a lease
 * owner as running, so the bugs here are a finished job that still reads as running and a busy re-queue that spends
 * an attempt or comes back at once.
 */
@RcaParkedSpringBootTest
record RcaJobRepositoryTest(RcaJobRepository jobs, TenantService tenants, JdbcClient jdbc) {

    private static final Instant AT = Instant.parse("2026-07-15T12:00:00Z");

    private String enqueue(String name) {
        String pid = TenantFixture.bootstrap(tenants, name).project().id();
        return jobs.createOrGet(pid, Ids.ulid(), "finding", "f-1", "secret_leak", AT, AT, AT, "user-1", null);
    }

    private void claim(String jobId) {
        // The drain is parked, so a wide batch reaches this job.
        assertTrue(
                jobs.claimBatch("rca-repo-test", 10_000, 600, 5).stream().anyMatch(j -> j.id().equals(jobId)),
                "the job was not claimable");
    }

    private Map<String, Object> row(String jobId) {
        return jdbc.sql("SELECT status, lease_owner, lease_expires_at, attempts, last_error FROM job WHERE id = :id")
                .param("id", jobId)
                .query()
                .singleRow();
    }

    /** A finished RCA read as running: done clears the lease owner the claim set. */
    @Test
    void doneClearsTheLeaseOwner() {
        String jobId = enqueue("rca-job-done");
        claim(jobId);

        jobs.markDone(jobId);

        Map<String, Object> row = row(jobId);
        assertEquals("done", row.get("status"));
        assertNull(row.get("lease_owner"));
    }

    /** A stopped RCA read as running: failed clears the lease owner the claim set and keeps the error. */
    @Test
    void failedClearsTheLeaseOwner() {
        String jobId = enqueue("rca-job-failed");
        claim(jobId);

        jobs.markFailed(jobId, "the provider rejected the key");

        Map<String, Object> row = row(jobId);
        assertEquals("failed", row.get("status"));
        assertNull(row.get("lease_owner"));
        assertEquals("the provider rejected the key", row.get("last_error"));
    }

    /**
     * A busy sandbox read as a failure: a re-queued job is claimed with no owner, so it reads Queued, gives back the
     * attempt its claim took, and is not claimable again until its delay passes.
     */
    @Test
    void aRequeuedJobWaitsItsDelayWithNoOwnerAndItsAttemptGivenBack() {
        String jobId = enqueue("rca-job-requeue");
        claim(jobId);
        Instant before = Instant.now();

        jobs.requeue(jobId, 30);

        Map<String, Object> row = row(jobId);
        assertEquals("claimed", row.get("status"));
        assertNull(row.get("lease_owner"));
        assertEquals(0, ((Number) row.get("attempts")).intValue());
        Instant expires = Instant.parse((String) row.get("lease_expires_at"));
        assertTrue(!expires.isBefore(before.plus(Duration.ofSeconds(29))), expires.toString());
        assertFalse(
                jobs.claimBatch("rca-repo-test", 10_000, 600, 5).stream().anyMatch(j -> j.id().equals(jobId)),
                "a re-queued job came back before its delay");
    }

    /** Round trip: a re-queue with no delay is claimed again as the attempt it gave back. */
    @Test
    void aRequeuedJobIsClaimedAgainAsTheSameAttempt() {
        String jobId = enqueue("rca-job-requeue-now");
        claim(jobId);

        jobs.requeue(jobId, 0);
        claim(jobId);

        Map<String, Object> row = row(jobId);
        assertEquals("claimed", row.get("status"));
        assertEquals("rca-repo-test", row.get("lease_owner"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
    }
}
