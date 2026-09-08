// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.open.jobqueue.JobRow;
import ai.tessary.evals.open.jobqueue.LeasePolicy;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance for the unified {@code job} table + {@link JobRepository}. Exercised against the real
 * pgvector Postgres (Testcontainers) so the schema applies for real. Proves the kind-scoped
 * SKIP-LOCKED claim: a pending job of a kind is claimed under a lease (attempts bumped), a done job is not
 * re-claimed, an expired-lease job under the cap is reclaimed, a job over the cap is dead-lettered, and a
 * claim of one kind never touches another kind's jobs.
 */
@SpringBootTest
class JobRepositoryTest {

    private static final LeasePolicy POLICY = new LeasePolicy(10, Duration.ofMinutes(5), 3);

    @Autowired
    TenantService tenants;

    @Autowired
    JobRepository jobs;

    private JobRow job(String pid, String kind, String status, String dedupe, int attempts, String leaseExpiresAt) {
        String now = Instant.now().toString();
        return new JobRow(
                Ids.ulid(),
                pid,
                kind,
                status,
                status.equals(JobRow.Status.CLAIMED) ? "worker-x" : null,
                leaseExpiresAt,
                attempts,
                null,
                dedupe,
                null,
                null,
                null,
                "{}",
                now,
                now);
    }

    @Test
    void pendingJobIsClaimedUnderLease_andDoneJobIsNotReclaimed() {
        String pid = TenantFixture.bootstrap(tenants, "job-claim").project().id();
        JobRow row = job(pid, JobRow.Kind.PULL, JobRow.Status.PENDING, "obs:1", 0, null);
        jobs.insert(row);

        List<JobRow> claimed = jobs.claimBatch(JobRow.Kind.PULL, "worker-1", POLICY);
        assertEquals(1, claimed.size());
        assertEquals(row.id(), claimed.get(0).id());
        assertEquals(JobRow.Status.CLAIMED, claimed.get(0).status());
        assertEquals(1, claimed.get(0).attempts(), "claim bumps attempts");

        jobs.markDone(row.id());
        assertTrue(jobs.claimBatch(JobRow.Kind.PULL, "worker-1", POLICY).isEmpty(), "a done job is never re-claimed");
    }

    @Test
    void expiredLeaseUnderCapIsReclaimed_butOverCapIsDeadLettered() {
        String pid = TenantFixture.bootstrap(tenants, "job-reclaim").project().id();
        String past = Instant.now().minus(Duration.ofHours(1)).toString();

        // Under the cap (attempts 1 < 3) with an expired lease → reclaimable.
        JobRow reclaimable = job(pid, JobRow.Kind.CLASSIFIER, JobRow.Status.CLAIMED, null, 1, past);
        jobs.insert(reclaimable);
        List<JobRow> claimed = jobs.claimBatch(JobRow.Kind.CLASSIFIER, "worker-2", POLICY);
        assertEquals(1, claimed.size(), "an expired-lease job under the cap is reclaimed");
        assertEquals(2, claimed.get(0).attempts());

        // At the cap (attempts 3 >= 3) with an expired lease → dead-lettered, not reclaimed.
        JobRow poison = job(pid, JobRow.Kind.CLASSIFIER, JobRow.Status.CLAIMED, null, 3, past);
        jobs.insert(poison);
        int dead = jobs.failExhausted(JobRow.Kind.CLASSIFIER, "hung mid-sweep", POLICY);
        assertEquals(1, dead, "the over-cap job is dead-lettered");
        assertEquals(
                JobRow.Status.FAILED, jobs.findById(poison.id()).orElseThrow().status());
    }

    @Test
    void claimIsScopedToItsKind() {
        String pid = TenantFixture.bootstrap(tenants, "job-kind").project().id();
        jobs.insert(job(pid, JobRow.Kind.CLASSIFIER, JobRow.Status.PENDING, null, 0, null));

        assertTrue(
                jobs.claimBatch(JobRow.Kind.PULL, "worker-3", POLICY).isEmpty(),
                "a claim for one kind never touches another kind's job");
    }
}
