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
 * Exercises the signal sweep queue's dead-letter budget against the real pgvector
 * Postgres (Testcontainers), on both exhaustion legs: a sweep job that keeps throwing (the {@code
 * /classify} transport-failure case) must dead-letter after {@code maxAttempts} consecutive
 * failures instead of being silently resurrected by every heartbeat's re-pend forever; a sweep job whose
 * worker keeps hanging (lease expiry, crash-reclaim) must get the same treatment. Both must
 * still recover automatically once the backend is healthy again.
 *
 * <p>Also covers {@link ClassifierJobRepository#listByProject} — the read behind the sweep-job health
 * endpoint: it must return a signal's job row scoped to its project, reflect a failure's
 * {@code status}/{@code attempts}/{@code lastError}, and never leak a job belonging to a different
 * project.
 *
 * <p>And the two writes a sweep makes to its job outside a page, {@link ClassifierJobRepository#releaseWithoutAttempt}
 * and {@link ClassifierJobRepository#recordCaughtUp}, change nothing for a worker whose lease another worker has
 * since taken, and the second merges into the payload rather than replacing it.
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

        // The bug this closes: ClassifierService#enqueueEnabled calls enqueue() unconditionally every
        // heartbeat. With a cooldown far in the future, that routine call must be a no-op on a dead job.
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

        // A worker that hangs mid-sweep and never reports back: each claim takes an already-expired lease
        // (negative leaseSeconds), so the crash-reclaim leg re-claims the row until attempts hits the cap.
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

        // The heartbeat's dead-letter sweep must park the job in the cooldown-gated 'dead'
        // state, not 'failed' (which the very next heartbeat's re-pend would resurrect: a retry loop with
        // no backoff, exactly what the fast-fail leg already prevents).
        assertTrue(jobs.failExhausted(MAX_ATTEMPTS) >= 1, "the exhausted job is dead-lettered");
        assertEquals(ClassifierJobRow.DEAD, status(jobId), "lease-expiry exhaustion lands in 'dead', not 'failed'");

        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        assertFalse(
                isClaimable(classifierId, "w2"),
                "the routine heartbeat enqueue must not resurrect a crash-reclaimed dead job under cooldown");

        backdateDeadLetter(classifierId);
        jobs.enqueue(pid, classifierId, 0); // cooldown elapsed — same automatic recovery as the fast-fail leg
        ClassifierJobRow revived = claimOne(classifierId, "w3");
        assertEquals(
                jobId,
                revived.id(),
                "once the cooldown elapses the same job revives, so a recovered worker resumes the sweep");
        // Leave the row terminal (done, attempts reset) so no over-cap expired-lease job leaks into later
        // tests' failExhausted sweeps.
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
        // Both components, or the keyset predicate `(created_at, id) > (cursorAt, cursorId)` is left
        // half-set and skips exactly the history the rewind exists to re-read.
        assertNull(after.cursorAt(), "the cursor timestamp is cleared");
        assertNull(after.cursorId(), "the cursor tiebreaker is cleared");
        assertEquals(ClassifierJobRow.PENDING, after.status(), "the job is due again without waiting for a heartbeat");
        assertTrue(isClaimable(classifierId, "w2"), "the next sweep re-reads from the beginning");
    }

    @Test
    void rewindCursor_cannotBeExpressedByMarkSwept() {
        // Guards the reason rewindCursor exists at all: markSwept COALESCEs a null cursor onto the
        // stored one, so the obvious "sweep with a null cursor" cannot reset anything.
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
        // A claimed job is mid-sweep and will markSwept its own cursor when it finishes, which would
        // silently undo a rewind applied underneath it. Better to skip it and report 0.
        String pid = project("signal-rewind-inflight");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        ClassifierJobRow inFlight = claimOne(classifierId, "w");

        assertEquals(0, jobs.rewindCursor(pid, classifierId), "an in-flight sweep is not rewound");

        // Leave the row terminal so it doesn't leak into another test's failExhausted sweep.
        jobs.markSwept(inFlight.id(), null, null);
    }

    @Test
    void rewindCursor_doesNotResurrectADeadLetteredJob() {
        // A rewind flipping status to 'pending' would walk straight through the dead-letter cooldown
        // floor that enqueue() deliberately refuses to cross — re-entering the fast-fail loop the
        // attempt cap exists to stop, on nothing more than a call-site fact landing.
        String pid = project("signal-rewind-dead");
        String classifierId = Ids.ulid();
        jobs.enqueue(pid, classifierId, LONG_COOLDOWN_SECONDS);
        driveToDeadLetter(pid, classifierId);
        assertFalse(isClaimable(classifierId, "w"), "setup: dead-lettered and under cooldown");

        assertEquals(0, jobs.rewindCursor(pid, classifierId), "a dead job is not rewound");
        assertFalse(isClaimable(classifierId, "w2"), "and stays dead until its cooldown elapses");
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
        jobs.markSwept(held.id(), null, null); // terminal, as rewindCursor_leavesAnInFlightSweepAlone leaves it
    }

    @Test
    void releaseWithoutAttempt_neverTakesAttemptsBelowZero() {
        // A sweep that marked itself swept (attempts reset to 0, lease_owner kept) and then hit an
        // unreachable encoder in its catch-up work hands the job back from 0.
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

    /** Claim the signal's job under a lease that is already expired (a hung worker, as the reclaim leg sees it). */
    private ClassifierJobRow claimOneWithExpiredLease(String classifierId) {
        List<ClassifierJobRow> batch = jobs.claimBatch("hung-worker", 50, -1, MAX_ATTEMPTS).stream()
                .filter(j -> j.classifierId().equals(classifierId))
                .toList();
        assertEquals(1, batch.size(), "exactly one due job for this signal");
        return batch.get(0);
    }

    /** Move the dead-letter moment an hour back, so it is unambiguously before any revival floor. */
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
