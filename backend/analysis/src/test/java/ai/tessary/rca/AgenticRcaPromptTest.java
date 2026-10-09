// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The prompt, the tools file and the schema the RCA agent reads, pinned where they name MCP tools, where Java fills
 * them, and where they must stay silent.
 *
 * <p>Two things are load-bearing here. First, this is the one agent the platform drives itself: {@code tools.md} is
 * the agent's tool catalogue, so a stale name in it does not degrade gracefully — it costs the run a turn on
 * {@code unknown tool} and teaches the agent the catalogue is unreliable.
 *
 * <p>Second, the CONTEXT FIREWALL. Layer 2 rules on the same finding with a cheap model and writes its verdict onto
 * the row; none of that may reach what this agent reads, including the fact that it happened. RCA is the only check
 * on the gate triage keeps, and an agent told "an earlier pass found this real" is not performing that check. The
 * vocabulary assertion below is the cheap half of the enforcement; the structural half is
 * {@code FindingRepository.findClaim}, which cannot read those columns at all.
 */
class AgenticRcaPromptTest {

    /**
     * The names the read-only cutover deleted, kept in step with {@code McpCapabilityGateTest}'s set of the same:
     * the triage/RCA tools, the last write, and the grader readers.
     */
    private static final Set<String> REMOVED_TOOLS = Set.of(
            "propose_grader_edit",
            "run_triage",
            "get_triage",
            "latest_triage",
            "list_rca_reports",
            "get_rca_report",
            "list_graders",
            "get_grader");

    /**
     * What the surface serves and the agent has to be pointed at. {@code get_finding_evidence} is the load-bearing
     * one: the dossier hydrates no trace, so an agent that does not call it has read nothing.
     */
    private static final List<String> EXPECTED_TOOLS = List.of(
            "get_finding_evidence",
            "get_trace",
            "get_span",
            "list_traces",
            "list_spans",
            "list_sessions",
            "get_session",
            "get_conversation",
            "list_cases",
            "get_case",
            "describe_dataset",
            "query_count",
            "query_facets",
            "query_timeseries",
            "query_search");

    /**
     * Layer 2's vocabulary, in the words the columns and the ruling use. A text containing any of them has told the
     * agent something about a pass it must not know ran.
     */
    static final List<String> TRIAGE_VOCABULARY =
            List.of("triage", "triage_verdict", "triage_action", "triage_summary", "triage_citations", "layer 2");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_]+\\}");

    private static String prompt(boolean repo, boolean baseline, boolean method) {
        return AgenticRcaEngine.buildPrompt(report(), "fnd-1", repo, baseline, method, 12, "traces", 15);
    }

    /** Every combination of the three snippets Java chooses. */
    static Stream<Arguments> runs() {
        List<Arguments> all = new ArrayList<>();
        for (boolean repo : new boolean[] {true, false}) {
            for (boolean baseline : new boolean[] {true, false}) {
                for (boolean method : new boolean[] {true, false}) {
                    all.add(Arguments.of(repo, baseline, method));
                }
            }
        }
        return all.stream();
    }

    /** Every text the agent reads besides the dossier's own finding and numbers, by name. */
    private static Map<String, String> everyAgentText() {
        Map<String, String> texts = new LinkedHashMap<>();
        runs().forEach(a -> {
            Object[] f = a.get();
            texts.put(
                    "prompt repo=" + f[0] + " baseline=" + f[1] + " method=" + f[2],
                    prompt((boolean) f[0], (boolean) f[1], (boolean) f[2]));
        });
        texts.put("tools.md", AgenticRcaEngine.TOOLS);
        texts.put("response_schema.json", AgenticRcaEngine.JSON_SCHEMA);
        for (String key : RcaMethodFilesTest.builtInKeys()) {
            String method = AgenticRcaEngine.method(key);
            if (method != null) texts.put("method.md for " + key, method);
        }
        return texts;
    }

    @Test
    void theToolsFileNamesNoToolTheSurfaceRemoved() {
        everyAgentText().forEach((name, text) -> {
            for (String gone : REMOVED_TOOLS) {
                assertFalse(
                        text.contains(gone),
                        name + " advertises " + gone + ", which the surface answers with `unknown tool` — the agent"
                                + " plans a call and burns a turn on it");
            }
        });
    }

    @Test
    void theToolsFileNamesEveryReaderTheAgentNeeds() {
        for (String tool : EXPECTED_TOOLS) {
            assertTrue(
                    AgenticRcaEngine.TOOLS.contains(tool),
                    "tools.md never names " + tool + ", so the agent will not reach for it");
        }
        assertTrue(AgenticRcaEngine.TOOLS.contains("READ-ONLY"), "tools.md should say the surface writes nothing");
    }

    /** The firewall, in the one place a leak would be invisible: the text the agent reads. */
    @Test
    void nothingTheAgentReadsDisclosesThatATriagePassExists() {
        everyAgentText().forEach((name, text) -> {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String word : TRIAGE_VOCABULARY) {
                assertFalse(
                        lower.contains(word),
                        name + " says '" + word + "' — the agent must not learn that an earlier pass ruled on this"
                                + " finding");
            }
        });
    }

    /** What this run is about reaches the agent: the finding, its window, its size and the time it has. */
    @ParameterizedTest(name = "repo={0} baseline={1} method={2}")
    @MethodSource("runs")
    void thisRunIsFilledIn(boolean repo, boolean baseline, boolean method) {
        String prompt = prompt(repo, baseline, method);

        assertTrue(prompt.contains("`fnd-1`"), "the finding id is what get_finding_evidence takes");
        assertTrue(prompt.contains("onset: 2026-05-04T00:00:00Z"), prompt);
        assertTrue(prompt.contains("last seen: 2026-05-08T00:00:00Z"), prompt);
        assertTrue(prompt.contains("flagged: 12 traces"), prompt);
        assertTrue(prompt.contains("time budget: 15 minutes"), prompt);
        assertTrue(prompt.contains("`dossier/tools.md`"), "tools.md is a first read");
        assertTrue(prompt.contains("`dossier/evidence.json`"), "evidence.json is a first read");
        assertFalse(prompt.contains("\n\n\n"), "a snippet left out leaves no blank gap");
    }

    /**
     * Java fills every placeholder but one: {@code {onset_commit}} is resolved by the sandbox after the clone, so it
     * must survive into a prompt with a repo and never appear in one without.
     */
    @ParameterizedTest(name = "repo={0} baseline={1} method={2}")
    @MethodSource("runs")
    void onlyTheOnsetCommitIsLeftForTheSandbox(boolean repo, boolean baseline, boolean method) {
        Matcher m = PLACEHOLDER.matcher(prompt(repo, baseline, method));
        List<String> left = new ArrayList<>();
        while (m.find()) left.add(m.group());

        assertEquals(repo ? List.of("{onset_commit}") : List.of(), left);
    }

    @ParameterizedTest(name = "repo={0} baseline={1} method={2}")
    @MethodSource("runs")
    void theMethodLineAppearsOnlyWhenTheFileShips(boolean repo, boolean baseline, boolean method) {
        String prompt = prompt(repo, baseline, method);

        assertEquals(method, prompt.contains(AgenticRcaEngine.METHOD_LINE));
        assertEquals(method, prompt.contains("dossier/method.md"), "no line points at a file that is not there");
    }

    /** {@code member} rows are not a comparison side; only the finding's own {@code baseline} rows are. */
    @ParameterizedTest(name = "repo={0} baseline={1} method={2}")
    @MethodSource("runs")
    void theBaselineSnippetAppearsOnlyWithBaselineRows(boolean repo, boolean baseline, boolean method) {
        String prompt = prompt(repo, baseline, method);

        assertEquals(baseline, prompt.contains("are the traffic before the onset"));
        assertTrue(prompt.contains("`member` rows are not\na comparison side"), "said whether or not there is one");
    }

    /** No repo is a lower ceiling, not a refusal: the agent is told the code side is unread and to attribute nothing. */
    @Test
    void aRepolessRunIsToldTheCodeSideIsUnread() {
        String prompt = prompt(false, false, true);

        assertTrue(prompt.contains("no repository connected"), "it must say why there is nothing to read");
        assertTrue(prompt.contains("The\ncode side of this run is unread"), prompt);
        assertTrue(prompt.contains("leave `attribution` null"), "nothing to attribute to");
        assertFalse(prompt.contains("current HEAD"), "no clone to read");
    }

    /** HEAD may postdate the finding, so the agent is sent to the code live at the onset. */
    @Test
    void aRunWithARepoIsSentToTheCodeLiveAtTheOnset() {
        String prompt = prompt(true, false, true);

        assertTrue(prompt.contains("`./repo/` is a clone"), prompt);
        assertTrue(prompt.contains("the code that matters is the code live at the onset"), prompt);
        assertTrue(prompt.contains("The commit live\nat the onset is `{onset_commit}`"), prompt);
        assertFalse(prompt.contains("no repository connected"), prompt);
    }

    /**
     * The standard of proof alone decides confidence: a finding with two flagged rows gets the same instructions as
     * one with forty, only the count differs.
     */
    @Test
    void theFlaggedCountChangesTheNumberAndNothingElse() {
        String few = AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, true, true, 2, "sessions", 15);
        String many = AgenticRcaEngine.buildPrompt(report(), "fnd-1", true, true, true, 40, "sessions", 15);

        assertEquals(many, few.replace("flagged: 2 sessions", "flagged: 40 sessions"));
    }

    /** One schema for every classifier, with exactly the verdicts and confidence levels the decision allows. */
    @Test
    void theSchemaIsTheOneContract() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(AgenticRcaEngine.JSON_SCHEMA);
        JsonNode props = schema.path("properties");
        JsonNode cause = props.path("causes").path("items");

        assertEquals(
                List.of("causes_identified", "no_cause_found"), texts(props.path("verdict").path("enum")));
        assertEquals(List.of("high", "medium"), texts(cause.path("properties").path("confidence").path("enum")));
        assertEquals(List.of("change", "standing"), texts(cause.path("properties").path("change").path("enum")));
        assertEquals(
                List.of("code", "prompt", "tool", "model", "traffic", "upstream", "data", "other"),
                texts(cause.path("properties").path("type").path("enum")));
        assertEquals(4, props.path("causes").path("maxItems").asInt());
        assertEquals(
                List.of("summary", "verdict", "causes", "ruled_out", "detailed_report"), texts(schema.path("required")));
        assertEquals(
                List.of(
                        "title",
                        "change",
                        "type",
                        "confidence",
                        "what_happens",
                        "how_it_caused_this",
                        "next_step",
                        "attribution",
                        "evidence_trace_ids",
                        "affected_count"),
                texts(cause.path("required")));
        assertEquals("string", props.path("ruled_out").path("items").path("type").asText(), "one sentence each");
        assertFalse(props.has("not_checked"), "what was not checked goes in detailed_report");
        assertFalse(props.has("checklist"), "there is no checklist");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static RcaReportRow report() {
        return new RcaReportRow(
                "rpt-1",
                "job-1",
                "finding",
                "fnd-1",
                "tool_error on search",
                "cs-checkout",
                BuiltInDetector.Kind.TOOL_ERROR,
                RcaReportRow.ReportKind.METRIC_MOVEMENT,
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
