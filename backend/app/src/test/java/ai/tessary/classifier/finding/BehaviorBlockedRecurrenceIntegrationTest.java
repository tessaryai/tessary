// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What happens when a cause a human called a deviation happens again.
 *
 * <p>This is the strongest claim the feature can make: the agent is doing something its owner
 * explicitly said it must not do. The uniqueness index is partial on {@code status='open'}, so
 * without care a blocked row no longer matches it and the next firing would insert a fresh finding,
 * resetting {@code first_seen_at}, restarting the count, discarding the human's verdict, and
 * re-enqueuing a Layer-2 microVM to re-litigate a settled question.
 *
 * <p>Every assertion below is on {@link FindingRepository}: the conflict target, the recurrence
 * arithmetic, the verdict stamp, the Layer-2 gate on the default view. {@code recordFiring} writes
 * the subject id into {@code subject_id}, an unconstrained column, so a bare ULID stands in for it
 * here.
 */
@SpringBootTest
class BehaviorBlockedRecurrenceIntegrationTest {

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    private static final String CAUSE_KIND = "novelty";
    private static final String CAUSE_KEY = "tool:exfiltrate_data";

    @Test
    @DisplayName("a firing after a human blocked the cause updates that row, and counts against it")
    void recurrenceAfterBlockAccumulatesOnTheSameFinding() {
        String pid = bootstrapGranted("blocked-recur").project().id();

        String subjectId = Ids.ulid();
        var first = record(pid, subjectId, 3);
        assertTrue(first.created(), "first sighting opens the finding");
        assertNull(first.humanVerdictAt(), "nobody has ruled yet");

        // The human calls it a deviation.
        findings.setStatus(
                pid, first.findingId(), FindingRow.Status.BLOCKED, Instant.now().toString());
        FindingRow blocked = findings.findById(pid, first.findingId()).orElseThrow();
        assertNotNull(blocked.humanVerdictAt(), "the ruling is stamped");
        assertEquals(0, blocked.recurrencesSinceVerdict(), "nothing has happened since, yet");

        // It happens again. Twice.
        var again = record(pid, subjectId, 5);
        assertEquals(first.findingId(), again.findingId(), "the SAME finding, not a new one beside it");
        record(pid, subjectId, 2);

        FindingRow after = findings.findById(pid, first.findingId()).orElseThrow();
        assertEquals(FindingRow.Status.BLOCKED, after.status(), "the human's verdict is not overwritten");
        assertEquals(
                7,
                after.recurrencesSinceVerdict(),
                "5 + 2 firings since the ruling — the number that says 'you blocked this and it kept happening'");
        assertEquals(10, after.sampleCount(), "lifetime count still accumulates: 3 before, 7 after");
        assertEquals(
                blocked.onsetAt(),
                after.onsetAt(),
                "first_seen_at is the cause's history, and a recurrence does not reset it");
    }

    @Test
    @DisplayName("Layer 2 is not re-run on a cause a human has already ruled on")
    void humanVerdictSuppressesReTriage() {
        String pid = bootstrapGranted("blocked-no-retriage").project().id();
        String subjectId = Ids.ulid();
        var first = record(pid, subjectId, 2);
        findings.setStatus(
                pid, first.findingId(), FindingRow.Status.BLOCKED, Instant.now().toString());

        var again = record(pid, subjectId, 4);
        // The sweep's gate reads exactly this. Non-null means "settled — do not spend a microVM, a repo
        // clone and an agent run re-litigating it", and the triage agent could not overturn it anyway:
        // its verdict is recorded as evidence and never writes the allowlist or resolves the finding.
        assertNotNull(
                again.humanVerdictAt(), "the recurrence carries the human verdict, which is what suppresses re-triage");
    }

    @Test
    @DisplayName("a recurring blocked finding survives the Layer-2 gate on the default view")
    void recurringBlockedFindingIsVisibleByDefault() {
        String pid = bootstrapGranted("blocked-visible").project().id();
        String subjectId = Ids.ulid();
        var first = record(pid, subjectId, 2);
        findings.setStatus(
                pid, first.findingId(), FindingRow.Status.BLOCKED, Instant.now().toString());
        record(pid, subjectId, 3);

        // confirmedOnly is what the page uses by default. The finding carries NO triage verdict —
        // by design, since the sweep skips Layer 2 once a human has ruled — so before the gate learned
        // about human verdicts, the strongest finding the feature can produce was the one it hid.
        // status="open" is what the page actually sends (BehaviorDrift.tsx -> listBehaviorFindings).
        // Asserting with status=null passed while the product filtered the finding out entirely:
        // `status='open' AND status='blocked'` is a contradiction, so the whole feature was unreachable.
        List<FindingRow> shown = findings.listByProject(pid, FindingRow.Status.OPEN, null, null, true, 50);
        assertEquals(1, shown.size(), "the recurring blocked cause is shown by the query the UI sends");
        assertNull(shown.get(0).triageVerdict(), "and it got there without a machine ruling");
    }

    @Test
    @DisplayName("a blocked cause that has NOT recurred stays out of the default view")
    void blockedWithoutRecurrenceIsNotShown() {
        String pid = bootstrapGranted("blocked-quiet").project().id();
        String subjectId = Ids.ulid();
        var first = record(pid, subjectId, 2);
        findings.setStatus(
                pid, first.findingId(), FindingRow.Status.BLOCKED, Instant.now().toString());

        // Acknowledged and not happening any more is the resolved case, not a live concern — the gate
        // must not turn every past ruling into permanent noise.
        assertTrue(findings.listByProject(pid, FindingRow.Status.OPEN, null, null, true, 50)
                .isEmpty());
    }

    @Test
    @DisplayName("re-ruling resets the verdict date and the count together")
    void reRulingResetsBothOrNeither() throws InterruptedException {
        String pid = bootstrapGranted("blocked-rerule").project().id();
        String subjectId = Ids.ulid();
        var first = record(pid, subjectId, 2);

        findings.setStatus(
                pid,
                first.findingId(),
                FindingRow.Status.ALLOWLISTED,
                Instant.now().toString());
        String firstVerdictAt =
                findings.findById(pid, first.findingId()).orElseThrow().humanVerdictAt();

        Thread.sleep(5);
        String secondVerdictAt = Instant.now().toString();
        findings.setStatus(pid, first.findingId(), FindingRow.Status.BLOCKED, secondVerdictAt);
        record(pid, subjectId, 3);

        FindingRow after = findings.findById(pid, first.findingId()).orElseThrow();
        assertEquals(
                secondVerdictAt,
                after.humanVerdictAt(),
                "the date must move with the count — otherwise the UI reports 'you marked this on <first "
                        + "verdict>, it happened N times since' where N counts from the SECOND");
        assertTrue(!secondVerdictAt.equals(firstVerdictAt), "the two rulings are distinguishable");
        assertEquals(3, after.recurrencesSinceVerdict(), "counted from the second ruling only");
    }

    /**
     * One firing of the same cause. The subject id is a bare ULID: {@code subject_id} carries no foreign
     * key, and the conflict target is {@code (project_id, classifier_key, cause_key)}, so what makes
     * these firings land on ONE row is the cause key — which is the thing under test.
     */
    private FindingRepository.Recorded record(String projectId, String subjectId, long delta) {
        return findings.recordFiring(
                Ids.ulid(),
                projectId,
                subjectId,
                CAUSE_KIND,
                CAUSE_KEY,
                FindingRow.GLOBAL_WORKFLOW,
                delta,
                null,
                null,
                "cs-a",
                Instant.now().toString());
    }

    /**
     * Bootstrap a tenant whose org has behavior drift switched on before its project is created.
     *
     * <p>Behavior drift defaults off, so without a grant these cases would assert the capability
     * default rather than the behaviour they name. The grant must precede the project because
     * project creation is what seeds the built-in classifiers: grant afterwards and the classifier
     * row is never inserted, leaving the test hunting findings from a classifier the project does
     * not have.
     */
    private TenantFixture.Setup bootstrapGranted(String name) {
        return TenantFixture.bootstrap(tenants, name, org -> {
            capabilities.grant(org.id(), Capability.BEHAVIOR_DRIFT);
        });
    }
}
