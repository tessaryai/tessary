// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The prompt the agentic lane hands the Claude-in-E2B session, pinned where it names MCP tools and where
 * it must stay silent.
 *
 * <p>Two things are load-bearing here. First, this is the one agent the platform drives itself: the MCP
 * paragraph is the agent's tool catalogue, so a stale name in it does not degrade gracefully — it costs
 * the run a turn on {@code unknown tool} and teaches the agent the catalogue is unreliable. Nothing else
 * pinned this string, which is how {@code run_triage} survived the sweep that updated the registry, the
 * {@code initialize} instructions and two javadocs.
 *
 * <p>Second, the CONTEXT FIREWALL. Layer 2 rules on the same finding with a cheap model and writes its
 * verdict onto the row; none of that may reach this prompt, including the fact that it happened. RCA is
 * the only check on the gate triage keeps, and an agent told "an earlier pass found this real" is not
 * performing that check. The vocabulary assertion below is the cheap half of the enforcement; the
 * structural half is {@code FindingRepository.findClaim}, which cannot read those columns at all.
 */
class AgenticRcaPromptTest {

    /**
     * The six names the read-only cutover deleted, kept in step with {@code McpCapabilityGateTest}'s set of
     * the same. Five were the triage/RCA tools (spend, and reports that now ride inline on the case that owns
     * them) and one was the last write.
     */
    private static final Set<String> REMOVED_TOOLS = Set.of(
            "propose_grader_edit",
            "run_triage",
            "get_triage",
            "latest_triage",
            "list_rca_reports",
            "get_rca_report",
            // Track A deleted graders, so the registry no longer serves these two either.
            "list_graders",
            "get_grader");

    /**
     * What the v2 surface added and the prompt has to point at, or the platform's own agent is left driving
     * the pre-v2 catalogue: it can fetch a trace by id but never find one, and never sees the cases. {@code
     * get_finding_evidence} is the load-bearing one now — the dossier no longer hydrates a single trace, so
     * an agent that does not call it has read nothing.
     */
    private static final List<String> EXPECTED_TOOLS = List.of(
            "get_finding_evidence",
            "get_trace",
            "get_span",
            "list_traces",
            "list_spans",
            "list_sessions",
            "get_session",
            "list_cases",
            "get_case",
            "describe_dataset",
            "query_count",
            "query_facets",
            "query_timeseries",
            "query_search");

    /**
     * Layer 2's vocabulary, in the words the columns and the ruling use. A prompt containing any of them
     * has told the agent something about a pass it must not know ran.
     */
    private static final List<String> TRIAGE_VOCABULARY =
            List.of("triage", "triage_verdict", "triage_action", "triage_summary", "triage_citations", "layer 2");

    @Test
    void theMcpParagraphNamesNoToolTheSurfaceRemoved() {
        String prompt = AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, 12, 40);

        for (String gone : REMOVED_TOOLS) {
            assertFalse(
                    prompt.contains(gone),
                    "the prompt advertises " + gone + ", which the surface answers with `unknown tool` — the"
                            + " agent plans a call and burns a turn on it");
        }
    }

    @Test
    void theMcpParagraphNamesEveryReaderTheAgentNeeds() {
        String prompt = AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, 12, 40);

        for (String tool : EXPECTED_TOOLS) {
            assertTrue(
                    prompt.contains(tool), "the prompt never names " + tool + ", so the agent will not reach for it");
        }
        assertTrue(prompt.contains("READ-ONLY"), "the prompt should say the surface writes nothing");
        // The finding id is what get_finding_evidence takes, and the window bounds are what make the
        // query tools usable at all.
        assertTrue(prompt.contains("fnd-1"), "the finding id is interpolated");
        assertTrue(prompt.contains("2026-05-01") && prompt.contains("2026-05-08"), "window bounds are interpolated");
    }

    /** The firewall, in the one place a leak would be invisible: the prompt text itself. */
    @Test
    void thePromptNeverDisclosesThatATriagePassExists() {
        for (boolean repoCloned : new boolean[] {true, false}) {
            String prompt = AgenticRcaEngine.buildPrompt(report(), "fnd-1", repoCloned, 12, 40)
                    .toLowerCase(Locale.ROOT);
            for (String word : TRIAGE_VOCABULARY) {
                assertFalse(
                        prompt.contains(word),
                        "the prompt says '" + word + "' — the agent must not learn that an earlier pass ruled on"
                                + " this finding, let alone what it ruled (repoCloned=" + repoCloned + ")");
            }
        }
    }

    /** No repo is a lower ceiling, not a refusal: the agent is told the code side is unreadable and is
     *  told not to invent it, rather than being pointed at a ./repo/ that does not exist. */
    @Test
    void aRepolessRunIsToldTheCodeSideIsUnread() {
        String prompt = AgenticRcaEngine.buildPrompt(report(), "fnd-1", false, 12, 40);

        assertFalse(
                prompt.contains("git -C ./repo"),
                "a repo-less run must not be told to run git on a clone it has not got");
        assertTrue(prompt.contains("no repository connected"), "it must say why there is nothing to read");
        // The evidence door is the half that survives without a repo — it is never conditional.
        assertTrue(prompt.contains("get_finding_evidence"), "MCP is the run's only substrate either way");
    }

    /** Thin evidence changes the instruction, not just the number: a rate over four traces is four traces. */
    @Test
    void aThinSideCapsConfidence() {
        assertTrue(
                AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, 2, 40).contains("cap every hypothesis"),
                "a two-trace baseline must cap confidence");
        assertFalse(
                AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, 40, 40).contains("cap every hypothesis"),
                "a well-evidenced finding gets no cap");
    }

    private static RcaReportRow report() {
        return new RcaReportRow(
                "rpt-1",
                "proj-1",
                "job-1",
                "finding",
                "fnd-1",
                "answer_relevance on checkout",
                "cs-checkout",
                "pass_rate",
                "2026-05-01T00:00:00Z",
                "2026-05-04T00:00:00Z",
                "2026-05-08T00:00:00Z",
                0.71,
                0.93,
                -0.22,
                "running",
                null,
                null,
                null,
                null,
                null,
                RcaReportRow.Engine.AGENTIC,
                "2026-05-08T01:00:00Z",
                null);
    }
}
