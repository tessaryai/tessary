// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.finding.BehaviorTriageJobRepository.FailedTriage;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The triage queue's writes against the real {@code job} table. A triage run boots a microVM, so the bugs
 * here cost money or findings: a claimed row read back with a column swapped, a failed run handed back with
 * no backoff (every attempt spent inside a minute), a release that spends an attempt or drives the count
 * below zero, and a dead-lettered run the findings page cannot tell from one still going.
 */
@SpringBootTest
class BehaviorTriageJobRepositoryTest {

    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    BehaviorTriageJobRepository jobs;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    /**
     * A claim reads back every column as the queue wrote it. A failed run goes back leased for its backoff
     * with its error cut to 500 characters; a release refunds the attempt, never below zero, and is due at
     * once; done clears the error.
     */
    @Test
    void aJobIsClaimedRetriedReleasedAndDoneWithEveryColumnReadBack() {
        String pid = TenantFixture.bootstrap(tenants, "triage-job-lifecycle")
                .project()
                .id();
        String findingId = Ids.ulid();
        String jobId = jobs.enqueue(pid, findingId, "2026-09-01T00:00:00Z").jobId();
        Instant beforeClaim = Instant.now();

        BehaviorTriageJobRow claimed = claimMine(pid);

        assertEquals(
                new BehaviorTriageJobRow(
                        jobId,
                        pid,
                        findingId,
                        "claimed",
                        "owner-a",
                        claimed.leaseExpiresAt(),
                        1,
                        null,
                        "2026-09-01T00:00:00Z",
                        claimed.updatedAt()),
                claimed);
        assertBetween(beforeClaim.plusSeconds(600), Instant.now().plusSeconds(600), claimed.leaseExpiresAt());

        Instant beforeRetry = Instant.now();
        jobs.markRetryable(jobId, "x".repeat(600), 30);
        Row retried = row(jobId);
        assertEquals("x".repeat(500), retried.lastError());
        assertEquals(1, retried.attempts(), "a retry keeps the attempt it spent");
        assertBetween(beforeRetry.plusSeconds(30), Instant.now().plusSeconds(30), retried.leaseExpiresAt());
        assertEquals(
                List.of(),
                jobs.claimBatch("owner-b", 500, 600, MAX_ATTEMPTS).stream()
                        .filter(j -> j.projectId().equals(pid))
                        .toList(),
                "a job backing off is not claimable until its lease runs out");

        jobs.releaseWithoutAttempt(jobId, "launcher down");
        jobs.releaseWithoutAttempt(jobId, null);
        Row released = row(jobId);
        assertEquals(0, released.attempts(), "two releases of one attempt refund it once, never below zero");
        assertNull(released.lastError());
        assertEquals(1, claimMine(pid).attempts(), "a released job is due at once and spends a fresh attempt");

        jobs.markDone(jobId);
        Row done = row(jobId);
        assertEquals("done", done.status());
        assertNull(done.lastError());
    }

    /**
     * A run that exhausted its attempts is reported per finding with its attempts and the last error it
     * recorded, so the findings page can say it failed and why; a finding with no dead run is absent.
     */
    @Test
    void aDeadLetteredTriageIsReportedWithItsAttemptsAndLastError() {
        String pid =
                TenantFixture.bootstrap(tenants, "triage-job-dead").project().id();
        String findingId = Ids.ulid();
        String jobId = jobs.enqueue(pid, findingId, Instant.now().toString()).jobId();
        claimMine(pid);
        jobs.markRetryable(jobId, "kind=timeout", 0);
        jobs.failExhausted(1);

        assertEquals(
                Map.of(
                        findingId,
                        new FailedTriage(
                                findingId,
                                1,
                                "exhausted: 1 attempts (hung or crashed mid-triage); last: kind=timeout")),
                jobs.failedByFinding(pid, List.of(findingId, Ids.ulid())));
    }

    private BehaviorTriageJobRow claimMine(String pid) {
        List<BehaviorTriageJobRow> mine = jobs.claimBatch("owner-a", 500, 600, MAX_ATTEMPTS).stream()
                .filter(j -> j.projectId().equals(pid))
                .toList();
        assertEquals(1, mine.size(), "exactly one due triage job for this project");
        return mine.get(0);
    }

    private record Row(
            String status, int attempts, @Nullable String lastError, String leaseExpiresAt) {}

    private Row row(String jobId) {
        return jdbc.sql("SELECT status, attempts, last_error, lease_expires_at FROM job WHERE id = :id")
                .param("id", jobId)
                .query((rs, n) -> new Row(
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getString("lease_expires_at")))
                .single();
    }

    private static void assertBetween(Instant from, Instant to, @Nullable String actual) {
        Instant at = Instant.parse(String.valueOf(actual));
        assertTrue(!at.isBefore(from) && !at.isAfter(to), actual + " is not between " + from + " and " + to);
    }
}
