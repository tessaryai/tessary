// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * When a finding's onset moves, the rule {@code CaseLedger.isNewSpell} reads to decide whether a resolved case
 * reopens. It is a SQL {@code CASE} inside an upsert, and both arms matter: an onset moving while still firing (a
 * recompute drifts an hour or two) reopens a just-closed case; an onset frozen for the row's lifetime keeps a case
 * shut on a genuinely new spell. The gap in {@code last_seen_at} is the only record of a recovery.
 */
@SpringBootTest
class BehaviorFindingOnsetTest {

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    private static final String CAUSE_KEY = ToolErrorEvidence.MEASURE + ":tool:search_docs:up";
    private static final String EVIDENCE = "{\"measure\":\"tool_error_rate\"}";

    /** {@code ToolErrorService.QUIET_WINDOW}. */
    private static final java.time.Duration QUIET = java.time.Duration.ofHours(6);

    @Test
    @DisplayName("a spell still running keeps its onset, however much the recomputed one drifts")
    void aRunningSpellKeepsItsOnset() {
        String pid = TenantFixture.bootstrap(tenants, "onset-running").project().id();
        Instant now = Instant.now();
        Instant onset = now.minus(20, ChronoUnit.HOURS);

        String id = record(pid, onset, now).findingId();

        // Five minutes later, the recomputed onset has drifted two hours: the freeze absorbs it.
        Instant later = now.plus(5, ChronoUnit.MINUTES);
        var second = record(pid, onset.plus(2, ChronoUnit.HOURS), later);

        assertEquals(id, second.findingId(), "one cause is one row");
        assertEquals(
                onset.toString(),
                firstSeenAt(pid, id),
                "the onset must not move while the detection is still firing, or every resolved case "
                        + "reopens on the next tick");
    }

    @Test
    @DisplayName("a spell that went quiet and came back moves its onset, so its case can reopen")
    void aReFireAfterAGapMovesTheOnset() {
        String pid = TenantFixture.bootstrap(tenants, "onset-refire").project().id();
        Instant now = Instant.now();
        Instant firstOnset = now.minus(30, ChronoUnit.HOURS);

        String id = record(pid, firstOnset, now.minus(20, ChronoUnit.HOURS)).findingId();

        // Twenty hours with no refresh, then it broke again.
        Instant secondOnset = now.minus(2, ChronoUnit.HOURS);
        var reFired = record(pid, secondOnset, now);

        assertEquals(id, reFired.findingId(), "a re-fire continues the same row rather than opening a second");
        assertEquals(
                secondOnset.toString(),
                firstSeenAt(pid, id),
                "the onset moved across the gap — without this the case stays shut on a genuinely new spell");
        assertTrue(
                Instant.parse(firstSeenAt(pid, id)).isAfter(firstOnset),
                "and it moved FORWARD, which is what isNewSpell tests");
    }

    /** One recompute pass: in a spell since {@code onset}, observed at {@code at}. */
    private FindingRepository.Recorded record(String projectId, Instant onset, Instant at) {
        return Objects.requireNonNull(findings.recordRecomputedCause(
                Ids.ulid(),
                projectId,
                CAUSE_KEY,
                1000,
                BehaviorSubstrateRepository.UNATTRIBUTED,
                onset.toString(),
                EVIDENCE,
                at.toString(),
                at.minus(QUIET).toString(),
                at.toString()));
    }

    private String firstSeenAt(String projectId, String findingId) {
        return findings.findById(projectId, findingId).orElseThrow().onsetAt();
    }
}
