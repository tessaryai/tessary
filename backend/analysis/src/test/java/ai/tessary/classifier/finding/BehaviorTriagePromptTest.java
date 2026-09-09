// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierMethodCard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the tool names in triage's MCP door, the way {@code AgenticRcaPromptTest} pins RCA's.
 *
 * <p><b>Why it exists.</b> Both engines splice a hand-written paragraph naming the tools the agent
 * should reach for, and both carry the same warning in their javadoc: a tool named here that the
 * surface has removed costs the agent a turn on {@code unknown tool}, and a reader the surface has
 * that the prompt omits is one it will never think to use. RCA's list survived the v0.27.0 read-only
 * cutover — which deleted six tools — precisely because a test held it. Triage's did not have one, so
 * it was the copy that would rot silently.
 *
 * <p>Deliberately a string test rather than a registry cross-check: the registry lives in
 * {@code surfaces}, which sits ABOVE {@code analysis} in the reactor, so this module cannot import it.
 * The names are duplicated on purpose and the duplication is the point — if the two lists disagree,
 * one of them is wrong and this says which.
 */
class BehaviorTriagePromptTest {

    /** The six the read-only cutover deleted. Kept in step with {@code AgenticRcaPromptTest}'s set. */
    private static final Set<String> REMOVED_TOOLS = Set.of(
            "propose_grader_edit", "run_triage", "get_triage", "latest_triage", "list_rca_reports", "get_rca_report");

    /**
     * What triage must be able to reach. {@code get_finding_evidence} is load-bearing: the dossier
     * carries counts and a method card and no rows at all, so an agent that never calls it has read
     * nothing and can only restate the claim back at us.
     */
    private static final List<String> EXPECTED_TOOLS = List.of(
            "get_finding_evidence",
            "get_trace",
            "get_span",
            "list_traces",
            "list_spans",
            "list_sessions",
            "get_session",
            "describe_dataset",
            "query_count",
            "query_facets",
            "query_timeseries",
            "query_search",
            "get_finding",
            "list_findings");

    @Test
    void theMcpDoorNamesNoToolTheSurfaceRemoved() {
        String door = BehaviorTriageEngine.whatYouHave("fnd-1", true);

        for (String gone : REMOVED_TOOLS) {
            assertFalse(
                    door.contains(gone),
                    "the door advertises " + gone + ", which the surface answers with `unknown tool` — the"
                            + " agent plans a call and burns a turn on it");
        }
    }

    @Test
    void theMcpDoorNamesEveryReaderTriageNeeds() {
        String door = BehaviorTriageEngine.whatYouHave("fnd-1", true);

        for (String tool : EXPECTED_TOOLS) {
            assertTrue(door.contains(tool), "the door never names " + tool + ", so the agent will not reach for it");
        }
        assertTrue(door.contains("fnd-1"), "the finding id is interpolated — it is what every evidence call takes");
    }

    /**
     * The claims the tool_error card makes that a run has already been misled by the absence of.
     *
     * <p>Each of these three sentences exists because a triage run reasoned wrongly without it and said
     * so in its ruling: it read `member` (426) against `n_cur` (336) as an inconsistency in the
     * detector, read `patterns[].ref = 0` as "this failure never happened before" when the reference
     * simply keeps no signatures, and had to discover for itself that three of five failures shared one
     * timestamp. The card is where a method fact lives; a test is what stops it drifting back out.
     */
    @Test
    void theToolErrorCardStatesTheThingsARunHasAlreadyBeenMisledBy() {
        String card = ClassifierMethodCard.forClassifier(BuiltInDetector.Kind.TOOL_ERROR);
        assertNotNull(card);

        assertTrue(card.contains("watermark"), "the two clocks go unstated and member > n_cur reads as a defect");
        assertTrue(
                card.contains("independent trial"),
                "nothing says correlated calls each add evidence, so simultaneity looks not worth checking");
        assertTrue(
                card.contains("`patterns`"),
                "nothing says what the patterns block describes, so a zero on its reference side reads as a"
                        + " claim that the failure is new");
    }

    /**
     * A verdict is defined by what it MEANS, never by a list of ways a claim can fail.
     *
     * <p>The bullets used to enumerate failure modes — "spread evenly across signatures that were always
     * there", "the sample is too thin". Two of those named the before-side signature comparison the
     * payload structurally cannot carry, and all of them narrow the agent to the list: an example in a
     * verdict definition becomes the thing it pattern-matches for. The run that found three simultaneous
     * calls behind one "rise" was told to look for no such thing.
     */
    /**
     * The preflight, and the word that ends a run which fails it.
     *
     * <p>An agent that cannot reach the read surface has nothing to rule on, and until `blocked` existed
     * its nearest available answer was `unclear` — which CLOSES the finding. One live run took it: it
     * closed a finding and folded 7,191 calls into a detector's reference having read no rows at all,
     * and said so in its own summary. The check has to be stated, and the way out of it has to be
     * offered beside the verdicts, or the agent reaches for a ruling instead.
     */
    @Test
    void everyLaneChecksItsPrerequisitesAndCanReportThemMissing() {
        assertTrue(
                BehaviorTriageEngine.rulingOptions().contains("`blocked`"),
                "the verdicts must offer the non-verdict, or an agent that cannot read rules anyway");

        // Every lane ends with this exact tail, so pinning it pins all three.
        String tail = BehaviorTriageEngine.commonTail();
        assertTrue(tail.contains("## Before you begin"), "every lane states the check before the work");
        assertTrue(tail.contains("get_finding_evidence"), "and names a call that proves the surface answers");
        assertTrue(tail.contains("`blocked`"), "and offers the word for when the surface does not answer");
        assertTrue(
                tail.indexOf("## Before you begin") < tail.indexOf("## The eight rules"),
                "the check comes before the rules that assume it passed");
    }

    @Test
    void theVerdictBulletsNameNoFailureModes() {
        String prompt = BehaviorTriageEngine.rulingOptions();

        assertTrue(prompt.contains("the claim holds"), "positive has to say what it means");
        assertTrue(prompt.contains("the claim does not hold"), "and so does negative");
        assertTrue(prompt.contains("does not settle it"), "and unclear");
        for (String enumerated : List.of("spread evenly", "too thin", "rare before", "signature")) {
            assertFalse(
                    prompt.contains(enumerated),
                    "the bullets name '" + enumerated + "' — a listed failure mode is the one the agent looks for");
        }
    }

    /**
     * The rate-shift explanation frames the CAUSE and defers the METHOD to the card.
     *
     * <p>It used to assert that the shift "stayed moved — a sustained change, not a bad afternoon". A
     * CUSUM tests no such thing: it fires when accumulated evidence crosses a threshold, and the run
     * that read this sentence dutifully tested persistence, found a 26-hour burst inside a 16-day
     * "sustained" spell, and ruled `negative` partly on a property we had invented for it.
     */
    @Test
    void theRateShiftExplanationClaimsNoPersistence() {
        String text = BehaviorTriageEngine.causeText(FindingRow.Cause.RATE_SHIFT);

        assertFalse(text.contains("sustained"), "the detector tests persistence nowhere");
        assertFalse(text.contains("stayed moved"), "nor does it test that the rate is still elevated");
        assertFalse(
                text.contains("rare before"),
                "the reference keeps no per-signature counts, so this asks for a comparison that cannot be made");
        assertTrue(text.contains("pinned in-control rate"), "what it does compare against");
    }

    /**
     * The three documents, named. {@code method.md} was written to the dossier and mentioned in no
     * prompt, so the agent had no reason to open the one file that says what an empty role means for the
     * detector it is ruling on — which is the exact misreading the card exists to prevent.
     */
    @Test
    void whatYouHaveNamesEveryFileTheDossierActuallyCarries() {
        String door = BehaviorTriageEngine.whatYouHave("fnd-1", true);

        assertTrue(door.contains("dossier/finding.md"), "the claim file is unnamed");
        assertTrue(door.contains("dossier/method.md"), "the method card is delivered but never announced");
        assertTrue(door.contains("dossier/state.json"), "the detector's numbers are unnamed");
        assertFalse(
                door.contains("evidence.json"),
                "`evidence` on this surface means the ROWS; naming the claim file that too is the collision"
                        + " the rename removed");
    }

    /** A user-authored classifier has no method card, and the prompt must not promise a file that is absent. */
    @Test
    void whatYouHaveOmitsTheMethodCardWhenThereIsNone() {
        assertFalse(
                BehaviorTriageEngine.whatYouHave("fnd-1", false).contains("dossier/method.md"),
                "promising a file the dossier does not carry costs the agent a turn on a missing read");
    }

    /**
     * The property this whole layer rests on: we hand over counts and a door, never an instance. A
     * prompt that names one trace decides which instance the investigation anchors on, and the agent
     * cannot tell our pick from a draw it made itself.
     */
    @Test
    void whatYouHaveTellsTheAgentItWasGivenNoRows() {
        String door = BehaviorTriageEngine.whatYouHave("fnd-1", true);

        assertTrue(
                door.contains("names an individual trace or span"),
                "the no-example contract is the reason the dossier looks thin — say so, or it reads as a gap");
        assertTrue(door.contains("You take the sample"), "and the agent has to know the draw is its own");
    }
}
