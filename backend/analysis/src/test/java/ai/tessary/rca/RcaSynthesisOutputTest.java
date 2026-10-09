// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The validator every {@link AgenticRcaEngine} run passes through, one for every classifier: receipts checked against
 * this finding's evidence, causes below {@code medium} dropped, the verdict normalised, and the ruled-out sentences
 * mapped into the stored shape.
 */
class RcaSynthesisOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every trace and session id in the finding's evidence: what a cause may cite. */
    private static final Set<String> TRACES = Set.of("tr-1", "tr-2", "tr-3");

    private static final Set<String> SESSIONS = Set.of("s-1", "s-2", "s-3");

    private static RcaSynthesisOutput.Parsed parse(String reply) {
        return RcaSynthesisOutput.parse(MAPPER, reply, TRACES, SESSIONS, true, "proj");
    }

    private static RcaSynthesisOutput.Parsed parseWithoutRepo(String reply) {
        return RcaSynthesisOutput.parse(MAPPER, reply, TRACES, SESSIONS, false, "proj");
    }

    private static String cause(String title, String confidence, int affected, String traces, String sessions) {
        return "{\"title\":\"" + title + "\",\"change\":\"standing\",\"type\":\"prompt\",\"confidence\":\""
                + confidence + "\",\"what_happens\":\"w\",\"how_it_caused_this\":\"h\",\"next_step\":\"n\","
                + "\"affected_count\":" + affected + ",\"evidence_trace_ids\":[" + traces + "],"
                + "\"evidence_session_ids\":[" + sessions + "],"
                + "\"attribution\":{\"path\":\"agent/prompt.md\",\"commit\":\"abc123\",\"excerpt\":\"Answer briefly.\"}}";
    }

    private static String reply(String verdict, String... causes) {
        return "{\"summary\":\"s\",\"verdict\":\"" + verdict + "\",\"detailed_report\":\"## r\",\"ruled_out\":[],"
                + "\"causes\":[" + String.join(",", causes) + "]}";
    }

    private static List<String> titles(RcaSynthesisOutput.Parsed out) {
        return out.causes().stream().map(Cause::title).toList();
    }

    @Test
    void aWellFormedCauseIsKeptWhole() {
        RcaSynthesisOutput.Parsed out =
                parse(reply("causes_identified", cause("Asks twice", "high", 2, "\"tr-1\"", "\"s-1\",\"s-2\"")));

        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, out.verdict());
        assertEquals("s", out.summary());
        assertEquals("## r", out.detailedReport());
        assertNull(out.verdictNote());
        assertEquals(
                List.of(new Cause(
                        "Asks twice",
                        "high",
                        "standing",
                        "prompt",
                        "w",
                        "h",
                        "n",
                        new Attribution(null, "agent/prompt.md", "abc123", "Answer briefly."),
                        List.of("tr-1"),
                        List.of("s-1", "s-2"),
                        2)),
                out.causes());
    }

    /** A hallucinated receipt is worse than none: unknown and repeated ids drop, in both lists. */
    @Test
    void receiptsKeepOnlyThisFindingsIdsOnce() {
        Cause c = parse(reply(
                        "causes_identified",
                        cause("t", "medium", 1, "\"tr-1\",\"tr-9\",\"tr-1\"", "\"s-2\",\"s-9\",\"s-2\"")))
                .causes()
                .get(0);

        assertEquals(List.of("tr-1"), c.evidenceTraceIds());
        assertEquals(List.of("s-2"), c.evidenceSessionIds());
    }

    /** Session grain: a cause citing only sessions is receipted. */
    @Test
    void aCauseCitingOnlySessionsSurvives() {
        RcaSynthesisOutput.Parsed out =
                parse(reply("causes_identified", cause("Sessions", "medium", 1, "", "\"s-1\"")));

        assertEquals(List.of("Sessions"), titles(out));
    }

    @Test
    void aCauseCitingNothingOfThisFindingIsDropped() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("Invented", "high", 5, "\"tr-9\"", "\"s-9\""),
                cause("Real", "medium", 1, "\"tr-2\"", "")));

        assertEquals(List.of("Real"), titles(out));
    }

    /** Below medium is not a cause: there is no low level. */
    @ParameterizedTest
    @ValueSource(strings = {"low", "certain", ""})
    void aCauseBelowMediumIsDropped(String confidence) {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("Weak", confidence, 9, "\"tr-1\"", ""),
                cause("Kept", "medium", 1, "\"tr-2\"", "")));

        assertEquals(List.of("Kept"), titles(out));
    }

    @Test
    void aCauseWithNoConfidenceIsDropped() {
        String text = reply(
                "causes_identified",
                "{\"title\":\"No level\",\"evidence_trace_ids\":[\"tr-1\"]}",
                cause("Kept", "high", 1, "\"tr-2\"", ""));

        assertEquals(List.of("Kept"), titles(parse(text)));
    }

    @Test
    void causesIdentifiedWithNoSurvivingCauseIsDowngradedToNoCauseFound() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("Invented", "high", 5, "\"tr-9\"", ""),
                cause("Low", "low", 1, "\"tr-1\"", "")));

        assertEquals(RcaReportRow.Verdict.NO_CAUSE_FOUND, out.verdict());
        assertTrue(out.causes().isEmpty());
        assertNotNull(out.verdictNote());
        assertTrue(out.verdictNote().contains("causes_identified"), out.verdictNote());
    }

    /** An old verdict or an invented one is normalised to the no-cause answer, not downgraded with a note. */
    @ParameterizedTest
    @ValueSource(strings = {"behavior_change", "traffic_shift", "inconclusive", "vibes", "no_cause_found"})
    void anyOtherVerdictReadsAsNoCauseFound(String verdict) {
        RcaSynthesisOutput.Parsed out = parse(reply(verdict));

        assertEquals(RcaReportRow.Verdict.NO_CAUSE_FOUND, out.verdict());
        assertNull(out.verdictNote(), verdict);
    }

    /** Ranked by confidence, then by flagged rows explained; a large medium cause never outranks a small high one. */
    @Test
    void highCausesRankFirstThenTheLargest() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("Big medium", "medium", 9, "\"tr-1\"", ""),
                cause("Small medium", "medium", 2, "\"tr-2\"", ""),
                cause("Small high", "high", 1, "\"tr-3\"", ""),
                cause("Big high", "high", 3, "\"tr-1\"", "")));

        assertEquals(List.of("Big high", "Small high", "Big medium", "Small medium"), titles(out));
    }

    /** A surviving cause makes the verdict causes_identified whatever the agent wrote, and the report says so. */
    @ParameterizedTest
    @ValueSource(strings = {"no_cause_found", "inconclusive"})
    void aSurvivingCauseCorrectsTheVerdictUpward(String verdict) {
        RcaSynthesisOutput.Parsed out = parse(reply(verdict, cause("Asks twice", "medium", 1, "\"tr-1\"", "")));

        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, out.verdict());
        assertNotNull(out.verdictNote());
        assertTrue(out.verdictNote().contains("corrected"), out.verdictNote());
    }

    /** `change` is what marks a cause as current-format, so a cause without a valid one is dropped. */
    @ParameterizedTest
    @ValueSource(strings = {"\"change\":\"changed\",\"type\":\"prompt\",", "\"type\":\"prompt\","})
    void aCauseWithoutAValidChangeIsDropped(String labels) {
        String unlabelled = cause("Unlabelled", "high", 1, "\"tr-1\"", "")
                .replace("\"change\":\"standing\",\"type\":\"prompt\",", labels);

        RcaSynthesisOutput.Parsed out = parse(reply("causes_identified", unlabelled));

        assertEquals(List.of(), titles(out), labels);
        assertTrue(out.verdictNote().contains("valid `change`"), out.verdictNote());
    }

    /** Catches a well-evidenced cause lost to an off-list type or a capitalised label. */
    @Test
    void labelsIgnoreCaseAndAnUnknownTypeReadsAsOther() {
        String loose = cause("Loose labels", "High", 1, "\"tr-1\"", "")
                .replace(
                        "\"change\":\"standing\",\"type\":\"prompt\",",
                        "\"change\":\" Change \",\"type\":\"tooling\",");

        Cause c = parse(reply("causes_identified", loose)).causes().get(0);

        assertEquals("high", c.confidence());
        assertEquals("change", c.change());
        assertEquals("other", c.type());
    }

    /** The schema caps causes at four; the parser holds the line when a reply ignores it, keeping the top four. */
    @Test
    void atMostFourCausesAreKept() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("One", "high", 5, "\"tr-1\"", ""),
                cause("Two", "high", 4, "\"tr-1\"", ""),
                cause("Three", "medium", 3, "\"tr-2\"", ""),
                cause("Four", "medium", 2, "\"tr-2\"", ""),
                cause("Five", "medium", 1, "\"tr-3\"", "")));

        assertEquals(List.of("One", "Two", "Three", "Four"), titles(out));
    }

    @Test
    void aCauseExplainsAtLeastTheRowsItCites() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("Undercounted traces", "medium", 0, "\"tr-1\",\"tr-2\",\"tr-3\"", ""),
                cause("Undercounted sessions", "medium", 1, "", "\"s-1\",\"s-2\""),
                cause("Big", "medium", 9, "\"tr-1\"", "")));

        assertEquals(List.of("Big", "Undercounted traces", "Undercounted sessions"), titles(out));
        assertEquals(3, out.causes().get(1).affectedCount());
        assertEquals(2, out.causes().get(2).affectedCount());
    }

    /** Without a clone there is no file or commit to point at; the excerpt the agent read in a span still stands. */
    @Test
    void withoutARepoTheAttributionKeepsNoPathOrCommit() {
        Cause c = parseWithoutRepo(reply("causes_identified", cause("t", "high", 1, "\"tr-1\"", "")))
                .causes()
                .get(0);

        assertEquals(new Attribution(null, null, null, "Answer briefly."), c.attribution());
    }

    @Test
    void anAttributionWithNothingInItIsNoAttribution() {
        String text = reply(
                "causes_identified",
                "{\"title\":\"t\",\"confidence\":\"high\",\"evidence_trace_ids\":[\"tr-1\"],\"change\":\"change\",\"type\":\"code\","
                        + "\"attribution\":{\"path\":\" \",\"commit\":null,\"excerpt\":\"\"}}");

        assertNull(parse(text).causes().get(0).attribution());
        String withoutRepo = reply(
                "causes_identified",
                "{\"title\":\"t\",\"confidence\":\"high\",\"evidence_trace_ids\":[\"tr-1\"],\"change\":\"change\",\"type\":\"code\","
                        + "\"attribution\":{\"path\":\"a.py\",\"commit\":\"abc\"}}");
        assertNull(
                parseWithoutRepo(withoutRepo).causes().get(0).attribution(), "path and commit cleared, nothing left");
    }

    /** Each ruled-out sentence is stored as one entry the "What else was checked" list reads, with no internal id shown. */
    @Test
    void ruledOutSentencesBecomeNumberedEntries() {
        String text = "{\"summary\":\"s\",\"verdict\":\"no_cause_found\",\"causes\":[],\"ruled_out\":["
                + "\"  The model did not change during the window.  \",\" \",null,"
                + "\"Traffic held steady.\"]}";

        assertEquals(
                List.of(
                        new RuledOutCheck(
                                "ruled_out_1",
                                true,
                                null,
                                RuledOutCheck.Assessment.RULED_OUT,
                                null,
                                "The model did not change during the window."),
                        RuledOutCheck.ruledOut(2, "Traffic held steady.")),
                parse(text).ruledOut());
    }

    /**
     * A 4m48s, $0.80 investigation was thrown away at its last step over one undeclared field. The schema allows
     * extra keys, and every other check here drops what it cannot accept.
     */
    @Test
    void unexpectedFieldsAnywhereInTheTreeDoNotSinkTheRun() {
        String text = "{\"summary\":\"s\",\"verdict\":\"causes_identified\",\"detailed_report\":\"## r\","
                + "\"confidence_overall\":\"high\",\"checklist\":[{\"check\":\"serving_model\"}],"
                + "\"causes\":[{\"title\":\"t\",\"confidence\":\"high\",\"evidence_trace_ids\":[\"tr-1\"],\"change\":\"change\",\"type\":\"code\","
                + "\"supporting_commits\":[\"abc123\"],\"attribution\":{\"kind\":\"code\",\"path\":\"a.py\"}}]}";

        RcaSynthesisOutput.Parsed out = parse(text);

        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, out.verdict());
        assertEquals("## r", out.detailedReport());
        assertEquals(List.of("tr-1"), out.causes().get(0).evidenceTraceIds());
        assertNull(out.causes().get(0).attribution().kind(), "a cause carries its kind on type now");
        assertEquals(List.of(), out.ruledOut());
    }

    /** The salvage path, for an envelope with no {@code structured_output} whose {@code result} is wrapped in prose. */
    @Test
    void aReplyWrappedInProseAndAFenceIsStillRead() {
        String text = "Here is the completed analysis:\n\n```json\n"
                + "{\"summary\":\"s\",\"verdict\":\"no_cause_found\",\"detailed_report\":\"## r\","
                + "\"causes\":[],\"ruled_out\":[]}\n```";

        RcaSynthesisOutput.Parsed out = parse(text);

        assertEquals(RcaReportRow.Verdict.NO_CAUSE_FOUND, out.verdict());
        assertEquals("s", out.summary());
        assertEquals("## r", out.detailedReport());
    }

    /**
     * When the brace-sliced salvage does not bind either, the run fails closed as UPSTREAM_FAILED with the first
     * parse failure.
     */
    @Test
    void proseAroundAnUnbindableObjectFailsClosedWithTheFirstFailure() {
        TessaryException ex =
                assertThrows(TessaryException.class, () -> parse("The answer is {verdict: no_cause_found} as above."));

        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
        assertTrue(
                ex.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException,
                String.valueOf(ex.getCause()));
    }

    /** A blank, backwards-braced or prose-only reply fails the run rather than binding to an empty report. */
    @ParameterizedTest
    @ValueSource(strings = {"   ", "} the braces are backwards {", "just prose"})
    void aReplyWithNothingToBindFailsClosed(String reply) {
        TessaryException ex = assertThrows(TessaryException.class, () -> parse(reply));

        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }

    /**
     * Optional fields null or blank degrade rather than fail: a blank summary falls back to the first cause's title,
     * a blank report to none, an untitled cause drops, blank prose reads as none, and missing lists read empty.
     */
    @Test
    void aSparseReplyDegradesFieldByField() {
        String reply = "{\"summary\":\"  \",\"verdict\":null,\"detailed_report\":\"  \",\"ruled_out\":null,"
                + "\"causes\":[{\"title\":null,\"confidence\":\"high\",\"evidence_trace_ids\":[\"tr-1\"]},"
                + "{\"title\":\"Canary model\",\"confidence\":\"medium\",\"change\":\"standing\",\"type\":\"data\","
                + "\"what_happens\":null,\"evidence_session_ids\":[\"s-1\"]}]}";

        RcaSynthesisOutput.Parsed out = parse(reply);

        assertEquals("Canary model", out.summary());
        assertNull(out.detailedReport());
        assertEquals(
                List.of(new Cause(
                        "Canary model",
                        "medium",
                        "standing",
                        "data",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of("s-1"),
                        1)),
                out.causes());
        assertEquals(List.of(), out.ruledOut());
    }

    /**
     * Catches the raw reply reaching the case caption: a blank summary with nothing to fall back on is stored as no
     * summary, never as the agent's JSON.
     */
    @Test
    void aBlankSummaryWithNoCauseIsNoSummaryRatherThanTheReply() {
        assertNull(parse("{\"summary\":\"\",\"verdict\":\"no_cause_found\",\"causes\":[]}")
                .summary());
        assertNull(parse("{\"verdict\":\"no_cause_found\"}").summary());
    }
}
