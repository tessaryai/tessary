// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
            // Graders were removed entirely, so the registry no longer serves these two either.
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

    /** Each lane's prompt builder, by repo presence, and whether the lane attributes causes to code. */
    static Stream<Arguments> builders() {
        Function<Boolean, String> metric = repo -> AgenticRcaEngine.buildPrompt(report(), "fnd-1", repo, 12, 40);
        Function<Boolean, String> frustration = repo -> AgenticRcaEngine.buildFrustrationPrompt(
                report(RcaReportRow.ReportKind.FRUSTRATION_CAUSES), "fnd-1", repo, 12, 12);
        Function<Boolean, String> groundedness = repo -> AgenticRcaEngine.buildGroundednessPrompt(
                report(RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES), "fnd-1", repo, 12);
        return Stream.of(
                Arguments.of("metric", metric, true),
                Arguments.of("frustration", frustration, true),
                Arguments.of("groundedness", groundedness, true));
    }

    /** The firewall, in the one place a leak would be invisible: the prompt text itself. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("builders")
    void thePromptNeverDisclosesThatATriagePassExists(
            String lane, Function<Boolean, String> build, boolean attributes) {
        for (boolean repoCloned : new boolean[] {true, false}) {
            String prompt = build.apply(repoCloned).toLowerCase(Locale.ROOT);
            for (String word : TRIAGE_VOCABULARY) {
                assertFalse(
                        prompt.contains(word),
                        "the " + lane + " prompt says '" + word + "' — the agent must not learn that an earlier pass"
                                + " ruled on this finding (repoCloned=" + repoCloned + ")");
            }
        }
    }

    /** No repo is a lower ceiling, not a refusal: the agent is told the code side is unread, not sent to ./repo/. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("builders")
    void aRepolessRunIsToldTheCodeSideIsUnread(String lane, Function<Boolean, String> build, boolean attributes) {
        String prompt = build.apply(false);

        assertFalse(prompt.contains("git -C ./repo"), "no clone to run git on");
        assertTrue(prompt.contains("no repository connected"), "it must say why there is nothing to read");
        assertTrue(prompt.contains("get_finding_evidence"), "MCP is the run's only substrate either way");
        if (attributes) {
            assertTrue(prompt.contains("kind to `unknown`"), "attribution falls to unknown without a repo");
        }
    }

    /** Thin evidence changes the instruction, not just the number: a rate over four traces is four traces. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("evidenceDepths")
    void aThinSideCapsConfidence(String evidence, Supplier<String> prompt, boolean capped) {
        assertEquals(capped, prompt.get().contains("cap every cause"), evidence);
    }

    static Stream<Arguments> evidenceDepths() {
        RcaReportRow frustration = report(RcaReportRow.ReportKind.FRUSTRATION_CAUSES);
        RcaReportRow groundedness = report(RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES);
        return Stream.of(
                Arguments.of(
                        "a two-trace baseline must cap confidence",
                        (Supplier<String>) () -> AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, 2, 40),
                        true),
                Arguments.of(
                        "a well-evidenced finding gets no cap",
                        (Supplier<String>) () -> AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, 40, 40),
                        false),
                Arguments.of(
                        "a few frustrated sessions cap confidence",
                        (Supplier<String>)
                                () -> AgenticRcaEngine.buildFrustrationPrompt(frustration, "fnd-1", true, 3, 3),
                        true),
                Arguments.of(
                        "many frustrated sessions get no cap",
                        (Supplier<String>)
                                () -> AgenticRcaEngine.buildFrustrationPrompt(frustration, "fnd-1", true, 30, 30),
                        false),
                Arguments.of(
                        "a few flagged traces cap confidence",
                        (Supplier<String>)
                                () -> AgenticRcaEngine.buildGroundednessPrompt(groundedness, "fnd-1", true, 3),
                        true),
                Arguments.of(
                        "many flagged traces get no cap",
                        (Supplier<String>)
                                () -> AgenticRcaEngine.buildGroundednessPrompt(groundedness, "fnd-1", true, 30),
                        false));
    }

    /** The two resources the groundedness branch sends: a schema whose causes cite traces, and its own rules. */
    @Test
    void theGroundednessResourcesArePresent() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(AgenticRcaEngine.GROUNDEDNESS_JSON_SCHEMA);
        JsonNode cause = schema.path("properties").path("causes").path("items");
        List<String> required = new ArrayList<>();
        cause.path("required").forEach(n -> required.add(n.asText()));
        assertTrue(required.contains("evidence_trace_ids"), required.toString());
        assertTrue(required.contains("affected_count"), required.toString());
        assertFalse(cause.path("properties").has("evidence_session_ids"), "no session receipts");
        assertFalse(AgenticRcaEngine.GROUNDEDNESS_JSON_SCHEMA.contains("session"));
    }

    private static RcaReportRow report() {
        return report(RcaReportRow.ReportKind.METRIC_MOVEMENT);
    }

    private static RcaReportRow report(String reportKind) {
        return new RcaReportRow(
                "rpt-1",
                "job-1",
                "finding",
                "fnd-1",
                "answer_relevance on checkout",
                "cs-checkout",
                "pass_rate",
                reportKind,
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
                null,
                RcaReportRow.Engine.AGENTIC,
                null,
                "2026-05-08T01:00:00Z",
                null);
    }
}
