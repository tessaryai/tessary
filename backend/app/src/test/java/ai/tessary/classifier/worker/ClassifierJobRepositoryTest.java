// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.model.JobStatus;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The sweep queue's dead-letter budget against real Postgres, on both legs: a job that keeps throwing and one whose
 * worker keeps hanging must dead-letter after {@code maxAttempts} rather than be resurrected by every heartbeat, then
 * recover once healthy.
 *
 * <p>Also {@link ClassifierJobRepository#listByProject}, scoped to its project with status, attempts, and last error;
 * and {@link ClassifierJobRepository#releaseWithoutAttempt} and {@link ClassifierJobRepository#recordCaughtUp}, which
 * change nothing for a worker whose lease was taken, the second merging into the payload.
 */
@SpringBootTest
class ClassifierJobRepositoryTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LONG_COOLDOWN_SECONDS = 3600;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    private String project(String name) {
        return TenantFixture.bootstrap(tenants, name).project().id();
    }

    @Test
    void listByProject_excludesOtherProjectsJobs() {
        String pid = project("signal-health-scope-a");
        String otherPid = project("signal-health-scope-b");
        jobs.enqueue(pid, Ids.ulid(), LONG_COOLDOWN_SECONDS);
        jobs.enqueue(otherPid, Ids.ulid(), LONG_COOLDOWN_SECONDS);

        List<ClassifierJobRow> rows = jobs.listByProject(pid);

        assertTrue(rows.stream().allMatch(r -> r.projectId().equals(pid)));
    }

    @Test
    void listByProject_reflectsFailureStatusAttemptsAndLastError() {
        String pid = project("signal-health-failure");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow claimed = jobs.claimBatch("owner-1", 10, 300, MAX_ATTEMPTS).stream()
                .filter(j -> j.classifierId().equals(classifierId))
                .findFirst()
                .orElseThrow();
        jobs.markFailed(claimed.id(), "classify: connection refused", MAX_ATTEMPTS);

        Optional<ClassifierJobRow> after = jobs.listByProject(pid).stream()
                .filter(j -> j.classifierId().equals(classifierId))
                .findFirst();

        assertTrue(after.isPresent());
        assertEquals(JobStatus.FAILED, after.get().status());
        assertEquals("classify: connection refused", after.get().lastError());
    }

    private ClassifierJobRow claimOne(String classifierId, String leaseOwner) {
        List<ClassifierJobRow> batch = jobs.claimBatch(leaseOwner, 50, 300, MAX_ATTEMPTS).stream()
                .filter(j -> j.classifierId().equals(classifierId))
                .toList();
        assertEquals(1, batch.size(), "exactly one due job for this signal");
        return batch.get(0);
    }

    private boolean isClaimable(String classifierId, String leaseOwner) {
        return jobs.claimBatch(leaseOwner, 50, 300, MAX_ATTEMPTS).stream()
                .anyMatch(j -> j.classifierId().equals(classifierId));
    }

    @Test
    void deadLetteredJob_isNotResurrectedByTheRoutineHeartbeatEnqueue() {
        String pid = project("signal-deadletter-noresurrect");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);

        driveToDeadLetter(pid, classifierId);
        assertFalse(isClaimable(classifierId, "w"), "the job is dead-lettered, not due");

        // enqueueEnabled calls enqueue() every heartbeat; on a dead job with a future cooldown that must be a no-op.
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        assertFalse(
                isClaimable(classifierId, "w"),
                "a dead-lettered job stays dead across routine heartbeat enqueue calls (no silent resurrect loop)");
    }

    @Test
    void leaseExpiryExhaustion_deadLettersWithTheSameCooldownAsFastFail() {
        String pid = project("signal-reclaim-cap");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);

        // A hung worker: each claim takes an already-expired lease, so the reclaim leg re-claims until the cap.
        ClassifierJobRow first = claimOneWithExpiredLease(classifierId);
        assertEquals(1, first.attempts(), "the first claim is attempt 1");
        String jobId = first.id();
        for (int attempt = 2; attempt <= MAX_ATTEMPTS; attempt++) {
            ClassifierJobRow job = claimOneWithExpiredLease(classifierId);
            assertEquals(attempt, job.attempts(), "each crash-reclaim round increments attempts by 1");
            assertEquals(jobId, job.id(), "the reclaim leg re-claims the same row, not a fresh one");
        }
        assertFalse(
                isClaimable(classifierId, "w2"),
                "at the cap the reclaim leg must stop re-claiming and leave the row for failExhausted");

        // Parked as 'dead', not 'failed', which the next heartbeat's re-pend would resurrect with no backoff.
        assertTrue(jobs.failExhausted(MAX_ATTEMPTS) >= 1, "the exhausted job is dead-lettered");
        assertEquals(ClassifierJobRow.DEAD, status(jobId), "lease-expiry exhaustion lands in 'dead', not 'failed'");

        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        assertFalse(
                isClaimable(classifierId, "w2"),
                "the routine heartbeat enqueue must not resurrect a crash-reclaimed dead job under cooldown");

        backdateDeadLetter(classifierId);
        jobs.enqueue(pid, classifierId, 0); // cooldown elapsed
        ClassifierJobRow revived = claimOne(classifierId, "w3");
        assertEquals(
                jobId,
                revived.id(),
                "once the cooldown elapses the same job revives, so a recovered worker resumes the sweep");
        // Leave the row terminal so it does not leak into later failExhausted sweeps.
        jobs.markSwept(revived.id(), null, null);
    }

    @Test
    void rewindCursor_dropsTheHighWaterMarkBackToTheBeginningAndRePends() {
        String pid = project("signal-rewind-cursor");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow swept = claimOne(classifierId, "w");
        jobs.markSwept(swept.id(), "2026-01-01T00:00:00Z", "obs-42");
        assertEquals(
                "obs-42",
                jobs.listByProject(pid).get(0).cursorId(),
                "setup: the sweep left a high-water mark past the observation history");

        assertEquals(1, jobs.rewindCursor(pid, classifierId), "the signal's job is rewound");

        ClassifierJobRow after = jobs.listByProject(pid).get(0);
        // Both halves, or the keyset predicate is half-set and skips the history the rewind re-reads.
        assertNull(after.cursorAt(), "the cursor timestamp is cleared");
        assertNull(after.cursorId(), "the cursor tiebreaker is cleared");
        assertEquals(ClassifierJobRow.PENDING, after.status(), "the job is due again without waiting for a heartbeat");
        assertTrue(isClaimable(classifierId, "w2"), "the next sweep re-reads from the beginning");
    }

    @Test
    void rewindCursor_cannotBeExpressedByMarkSwept() {
        // markSwept COALESCEs a null cursor onto the stored one, so it cannot reset anything.
        String pid = project("signal-rewind-vs-marksweep");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow job = claimOne(classifierId, "w");
        jobs.markSwept(job.id(), "2026-01-01T00:00:00Z", "obs-9");

        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow again = claimOne(classifierId, "w");
        jobs.markSwept(again.id(), null, null);

        assertEquals(
                "obs-9",
                jobs.listByProject(pid).get(0).cursorId(),
                "an empty sweep leaves the high-water mark untouched — only rewindCursor moves it back");
    }

    @Test
    void rewindCursor_leavesAnInFlightSweepAlone() {
        // A claimed job would markSwept over the rewind, so it is skipped and reports 0.
        String pid = project("signal-rewind-inflight");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow inFlight = claimOne(classifierId, "w");

        assertEquals(0, jobs.rewindCursor(pid, classifierId), "an in-flight sweep is not rewound");

        // Leave the row terminal.
        jobs.markSwept(inFlight.id(), null, null);
    }

    @Test
    void rewindCursor_doesNotResurrectADeadLetteredJob() {
        // A rewind to 'pending' would bypass the dead-letter cooldown enqueue() refuses to cross.
        String pid = project("signal-rewind-dead");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        driveToDeadLetter(pid, classifierId);
        assertFalse(isClaimable(classifierId, "w"), "setup: dead-lettered and under cooldown");

        assertEquals(0, jobs.rewindCursor(pid, classifierId), "a dead job is not rewound");
        assertFalse(isClaimable(classifierId, "w2"), "and stays dead until its cooldown elapses");
    }

    @Test
    void rewindCursor_isANoOpForASignalThatHasNeverSwept() {
        String pid = project("signal-rewind-nojob");
        assertEquals(
                0,
                jobs.rewindCursor(pid, Ids.ulid()),
                "no job row means no history to re-read — the first sweep already starts from a null cursor");
    }

    @Test
    void releaseWithoutAttempt_byAWorkerWhoseLeaseExpired_leavesTheNewHoldersJobAlone() {
        String pid = project("signal-release-stale-owner");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        claimOneWithExpiredLease(classifierId); // "hung-worker" stalls past its lease
        ClassifierJobRow held = claimOne(classifierId, "w-new"); // the reclaim leg hands it on

        jobs.releaseWithoutAttempt(held.id(), "hung-worker");

        assertEquals(held, jobs.findByClassifier(pid, classifierId).orElseThrow());
        jobs.markSwept(held.id(), null, null); // terminal
    }

    @Test
    void releaseWithoutAttempt_neverTakesAttemptsBelowZero() {
        // A sweep that marked itself swept (attempts 0, owner kept) then hit an unreachable encoder hands the job
        // back from 0.
        String pid = project("signal-release-floor");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow claimed = claimOne(classifierId, "w");
        jobs.markSwept(claimed.id(), null, null);

        jobs.releaseWithoutAttempt(claimed.id(), "w");

        ClassifierJobRow after = jobs.findByClassifier(pid, classifierId).orElseThrow();
        assertEquals(0, after.attempts());
        assertNull(after.leaseOwner());
    }

    @Test
    void recordCaughtUp_byAWorkerWhoseLeaseExpired_writesNothing() {
        String pid = project("signal-caught-up-stale-owner");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        claimOneWithExpiredLease(classifierId);
        ClassifierJobRow held = claimOne(classifierId, "w-new");

        jobs.recordCaughtUp(held.id(), "hung-worker", Instant.parse("2026-09-01T12:00:00Z"));

        assertEquals(Optional.empty(), jobs.caughtUpAt(pid, classifierId));
        assertEquals(List.of("classifier_id"), payloadKeys(held.id()));
        jobs.markSwept(held.id(), null, null);
    }

    @Test
    void recordCaughtUp_mergesIntoThePayloadAndKeepsTheClassifierId() {
        String pid = project("signal-caught-up-merge");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow claimed = claimOne(classifierId, "w");
        jobs.markSwept(claimed.id(), null, null);

        jobs.recordCaughtUp(claimed.id(), "w", Instant.parse("2026-09-01T12:00:00Z"));
        jobs.recordCaughtUp(claimed.id(), "w", Instant.parse("2026-09-01T12:30:00Z"));

        assertEquals(List.of("caught_up_at", "classifier_id"), payloadKeys(claimed.id()));
        assertEquals(
                classifierId,
                jobs.findByClassifier(pid, classifierId).orElseThrow().classifierId());
        assertEquals(Optional.of(Instant.parse("2026-09-01T12:30:00Z")), jobs.caughtUpAt(pid, classifierId));
    }

    private List<String> payloadKeys(String jobId) {
        return jdbc.sql("SELECT k FROM job, jsonb_object_keys(payload) AS k WHERE id = :id ORDER BY k")
                .param("id", jobId)
                .query(String.class)
                .list();
    }

    /** Drive a job through {@code MAX_ATTEMPTS} consecutive fast failures until it dead-letters. */
    private void driveToDeadLetter(String projectId, String classifierId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ClassifierJobRow job = claimOne(classifierId, "w");
            boolean deadLettered = jobs.markFailed(job.id(), "launcher /classify transport failure", MAX_ATTEMPTS);
            if (attempt < MAX_ATTEMPTS) {
                jobs.enqueue(projectId, classifierId, LONG_COOLDOWN_SECONDS);
            } else {
                assertTrue(deadLettered, "setup: expected the job to be dead-lettered by now");
            }
        }
    }

    /** Claim under an already-expired lease, as the reclaim leg sees a hung worker. */
    private ClassifierJobRow claimOneWithExpiredLease(String classifierId) {
        List<ClassifierJobRow> batch = jobs.claimBatch("hung-worker", 50, -1, MAX_ATTEMPTS).stream()
                .filter(j -> j.classifierId().equals(classifierId))
                .toList();
        assertEquals(1, batch.size(), "exactly one due job for this signal");
        return batch.get(0);
    }

    /** An hour back, before any revival floor. */
    private void backdateDeadLetter(String classifierId) {
        jdbc.sql("UPDATE job SET updated_at = :at WHERE kind = 'classifier' AND dedupe_key = :sid")
                .param("at", Instant.now().minus(Duration.ofHours(1)).toString())
                .param("sid", classifierId)
                .update();
    }

    private String status(String jobId) {
        return jdbc.sql("SELECT status FROM job WHERE id = :id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }
}
