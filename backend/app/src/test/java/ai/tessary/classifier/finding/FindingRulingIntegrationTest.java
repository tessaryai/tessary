// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
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
 * Decision 1's central claim, against the real schema: a ruling freezes the finding by construction.
 *
 * <p>Every test here is a variant of the same fact — {@code ux_finding_live}'s arbiter is
 * {@code status = 'open' AND triage_verdict IS NULL}, so once a verdict lands, machine or human, the
 * row can never be conflicted onto again. A positive stays open (it backs a case once one exists to
 * join, which the async case reconciler picks up on its own cadence); a negative closes; and either
 * way the SAME cause firing again opens a fresh row rather than mutating the settled one.
 */
@SpringBootTest
class FindingRulingIntegrationTest {

    @Autowired
    FindingRepository findings;

    @Autowired
    BehaviorTriageSource behaviorTriage;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    private static final String GRAM = "gram-ruling";

    @Test
    @DisplayName("triage positive stays open, and is what a case source reads as confirmed")
    void triagePositiveStaysOpen() {
        Project p = project("ruling-triage-positive");
        String findingId = firing(p, GRAM);

        behaviorTriage.recordVerdict(p.id(), findingId, verdict("positive", "sound"), null, now());

        FindingRow row = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(FindingRow.Status.OPEN, row.status());
        assertEquals(FindingRow.TriageVerdict.POSITIVE, row.triageVerdict());
        assertNull(row.humanVerdictAt(), "a machine ruling never stamps the human column");
        assertEquals(
                1,
                findings.listConfirmed(p.id(), List.of(BuiltInDetector.Kind.BEHAVIOR_DRIFT), null, 10)
                        .size(),
                "a positive, open finding is exactly what the case gate reads as confirmed");
    }

    @Test
    @DisplayName("triage negative closes the finding")
    void triageNegativeCloses() {
        Project p = project("ruling-triage-negative");
        String findingId = firing(p, GRAM);

        behaviorTriage.recordVerdict(p.id(), findingId, verdict("negative", "an artifact"), null, now());

        FindingRow row = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(FindingRow.Status.CLOSED, row.status());
        assertEquals(FindingRow.TriageVerdict.NEGATIVE, row.triageVerdict());
    }

    @Test
    @DisplayName("a firing after a ruling opens a fresh row rather than mutating the settled one")
    void aFiringAfterRulingOpensAFreshRow() {
        Project p = project("ruling-refire");
        String first = firing(p, GRAM);
        behaviorTriage.recordVerdict(p.id(), first, verdict("negative", "an artifact"), null, now());

        String second = firing(p, GRAM);

        assertNotEquals(first, second, "the closed row left the live arbiter, so the firing opened a new one");
        FindingRow refired = findings.findById(p.id(), second).orElseThrow();
        assertEquals(FindingRow.Status.OPEN, refired.status());
        assertNull(refired.triageVerdict(), "the new row starts unruled, exactly like a first firing");
        FindingRow closed = findings.findById(p.id(), first).orElseThrow();
        assertEquals(FindingRow.Status.CLOSED, closed.status(), "the earlier ruling is untouched");
    }

    @Test
    @DisplayName("a new finding under the same cause is triaged independently of the one before it")
    void aNewFindingIsTriagedIndependently() {
        Project p = project("ruling-independent");
        String first = firing(p, GRAM);
        behaviorTriage.recordVerdict(p.id(), first, verdict("negative", "an artifact"), null, now());
        String second = firing(p, GRAM);

        behaviorTriage.recordVerdict(p.id(), second, verdict("positive", "sound this time"), null, now());

        assertEquals(
                FindingRow.TriageVerdict.NEGATIVE,
                findings.findById(p.id(), first).orElseThrow().triageVerdict());
        assertEquals(
                FindingRow.TriageVerdict.POSITIVE,
                findings.findById(p.id(), second).orElseThrow().triageVerdict());
    }

    @Test
    @DisplayName("a person's Real deviation writes positive and human_verdict_at, and stays open")
    void personsRealDeviationWritesPositive() {
        Project p = project("ruling-human-positive");
        String findingId = firing(p, GRAM);

        var view = behaviorTriage.resolve(p.id(), findingId, BehaviorResolutionRequest.NOT_EXPECTED, "user-1");

        assertNotNull(view.orElseThrow());
        FindingRow row = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(FindingRow.Status.OPEN, row.status());
        assertEquals(FindingRow.TriageVerdict.POSITIVE, row.triageVerdict());
        assertNotNull(row.humanVerdictAt(), "the ruling is stamped, which is what a case is opened off");
    }

    @Test
    @DisplayName("a person's Legitimate closes the finding negative")
    void personsLegitimateClosesNegative() {
        Project p = project("ruling-human-negative");
        String findingId = firing(p, GRAM);

        behaviorTriage.resolve(p.id(), findingId, BehaviorResolutionRequest.EXPECTED, "user-1");

        FindingRow row = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(FindingRow.Status.CLOSED, row.status());
        assertEquals(FindingRow.TriageVerdict.NEGATIVE, row.triageVerdict());
        assertNotNull(row.humanVerdictAt());
    }

    @Test
    @DisplayName("a verb on an already-closed finding returns 409 FINDING_CLOSED")
    void aVerbOnAClosedFindingIs409() {
        Project p = project("ruling-409-closed");
        String findingId = firing(p, GRAM);
        behaviorTriage.resolve(p.id(), findingId, BehaviorResolutionRequest.EXPECTED, "user-1");

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> behaviorTriage.resolve(p.id(), findingId, BehaviorResolutionRequest.EXPECTED, "user-2"));

        assertEquals(ClassifierError.FINDING_CLOSED, e.error());
    }

    @Test
    @DisplayName("a verb on an open, already-ruled-positive finding also returns 409")
    void aVerbOnAnOpenRuledFindingIsAlso409() {
        Project p = project("ruling-409-open-ruled");
        String findingId = firing(p, GRAM);
        behaviorTriage.recordVerdict(p.id(), findingId, verdict("positive", "sound"), null, now());

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> behaviorTriage.resolve(p.id(), findingId, BehaviorResolutionRequest.EXPECTED, "user-1"));

        assertEquals(ClassifierError.FINDING_CLOSED, e.error());
    }

    @Test
    @DisplayName("a late triage verdict loses the race silently: it changes nothing on an already-ruled finding")
    void aLateTriageVerdictIsDiscarded() {
        Project p = project("ruling-late-verdict");
        String findingId = firing(p, GRAM);
        behaviorTriage.resolve(p.id(), findingId, BehaviorResolutionRequest.EXPECTED, "user-1");

        // The run already happened by the time the human pressed the button; this is what the worker's
        // own recordVerdict call looks like landing after the race is already lost.
        behaviorTriage.recordVerdict(
                p.id(), findingId, verdict("positive", "a machine opinion nobody asked for"), null, now());

        FindingRow row = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(FindingRow.Status.CLOSED, row.status(), "the human's ruling stands");
        assertEquals(FindingRow.TriageVerdict.NEGATIVE, row.triageVerdict(), "the late machine verdict never landed");
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private Project project(String slug) {
        // The grant precedes the project: project creation is what seeds the built-in classifiers, and
        // requireReachableFinding refuses a classifier the org's capability layer withholds.
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.BEHAVIOR_DRIFT))
                .project();
    }

    private String firing(Project p, String gram) {
        return findings.recordFiring(
                        Ids.ulid(),
                        p.id(),
                        "profile-" + p.id(),
                        FindingRow.Cause.NOVELTY,
                        gram,
                        "workflow-1",
                        5,
                        null,
                        null,
                        "cs-1",
                        now())
                .findingId();
    }

    private static BehaviorTriageVerdict verdict(String verdict, String summary) {
        return new BehaviorTriageVerdict(verdict, summary, List.of());
    }

    private static String now() {
        return Instant.now().toString();
    }
}
