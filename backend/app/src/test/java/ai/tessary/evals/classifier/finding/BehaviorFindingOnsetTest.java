// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.evals.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * When a finding's onset moves and when it must not — the rule {@code CaseLedger.isNewSpell} reads to
 * decide whether a resolved case reopens.
 *
 * <p>Against the real database because the rule IS a SQL {@code CASE} inside an upsert, and both arms
 * of it are load-bearing in opposite directions:
 *
 * <ul>
 *   <li>An onset that moves while a detection is STILL FIRING reopens a case within one tick of a human
 *       closing it, which makes "resolve" look broken. A recomputed onset drifts by an hour or two as
 *       the replay window slides, so this is not hypothetical.
 *   <li>An onset frozen for the row's whole LIFETIME means a detection that recovered and re-fired
 *       inside the reopen window presents an onset that has not moved, so its case stays shut on a
 *       shift that is genuinely new — silently, since nothing anywhere records the miss.
 * </ul>
 *
 * <p>The gap in {@code last_seen_at} is what tells the two apart, and it is not a proxy for a recovery
 * so much as the only record of one: nothing writes "this came back", a recovered detection simply
 * stops appearing and stops being refreshed.
 */
@SpringBootTest
class BehaviorFindingOnsetTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    private static final String CAUSE_KEY = ToolErrorEvidence.MEASURE + ":tool:search_docs:up";
    private static final String EVIDENCE = "{\"measure\":\"tool_error_rate\"}";

    /** Six hours, as {@code ToolErrorService.QUIET_WINDOW} sets it. */
    private static final java.time.Duration QUIET = java.time.Duration.ofHours(6);

    @Test
    @DisplayName("a spell still running keeps its onset, however much the recomputed one drifts")
    void aRunningSpellKeepsItsOnset() {
        String pid = TenantFixture.bootstrap(tenants, "onset-running").project().id();
        Instant now = Instant.now();
        Instant onset = now.minus(20, ChronoUnit.HOURS);

        String id = record(pid, onset, now).findingId();

        // The next pass, five minutes later, with an onset the replay has recomputed two hours later —
        // the drift the freeze exists to absorb.
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

        // The detection dropped out: for the next twenty hours no pass found this tool in a spell, so
        // nothing refreshed the row and last_seen_at stopped advancing. Then it broke again.
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

    @Test
    @DisplayName("a gap shorter than the quiet window is not a recovery")
    void aShortGapIsNotARecovery() {
        String pid =
                TenantFixture.bootstrap(tenants, "onset-shortgap").project().id();
        Instant now = Instant.now();
        Instant onset = now.minus(30, ChronoUnit.HOURS);

        String id = record(pid, onset, now.minus(1, ChronoUnit.HOURS)).findingId();
        // An hour without a refresh — a slow pass, a restart, a project that went briefly quiet. Well
        // inside the six-hour horizon, so the spell is the same spell.
        record(pid, now.minus(10, ChronoUnit.MINUTES), now);

        assertEquals(onset.toString(), firstSeenAt(pid, id), "a short gap leaves the spell alone");
    }

    /** One recompute pass: the tool is in a spell that began at {@code onset}, observed at {@code at}. */
    private FindingRepository.Recorded record(String projectId, Instant onset, Instant at) {
        return findings.recordRecomputedCause(
                Ids.ulid(),
                projectId,
                CAUSE_KEY,
                1000,
                BehaviorSubstrateRepository.UNATTRIBUTED,
                onset.toString(),
                EVIDENCE,
                at.minus(QUIET).toString(),
                at.toString());
    }

    private String firstSeenAt(String projectId, String findingId) {
        return findings.findById(projectId, findingId).orElseThrow().onsetAt();
    }
}
