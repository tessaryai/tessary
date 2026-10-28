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
import ai.tessary.rca.RcaDtos.Coverage;
import ai.tessary.rca.RcaDtos.RoleCount;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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

    /** The finding's flagged rows, one comparison trace, and the counts finding.md showed the agent. */
    private static final Set<String> TRACES = Set.of("tr-1", "tr-2", "tr-3");

    private static final Set<String> SESSIONS = Set.of("s-1", "s-2", "s-3");

    private static final AgenticRcaEngine.Evidence EVIDENCE = new AgenticRcaEngine.Evidence(
            TRACES, SESSIONS, Set.of("tb-1"), Set.of(), true, Map.of("witness", 3L, "baseline", 1L), 3, "traces");

    /** A frustration-like subject: two flagged sessions, whose turns are the flagged traces. */
    private static final AgenticRcaEngine.Evidence SESSION_GRAIN = new AgenticRcaEngine.Evidence(
            TRACES, Set.of("s-1", "s-2"), Set.of(), Set.of(), false, Map.of("witness", 2L), 2, "sessions");

    private static RcaSynthesisOutput.Parsed parse(String reply) {
        return RcaSynthesisOutput.parse(MAPPER, reply, EVIDENCE, true, "proj");
    }

    private static RcaSynthesisOutput.Parsed parseWithoutRepo(String reply) {
        return RcaSynthesisOutput.parse(MAPPER, reply, EVIDENCE, false, "proj");
    }

    private static String cause(String title, String confidence, int affected, String traces, String sessions) {
        return cause(title, confidence, (Integer) affected, traces, sessions);
    }

    private static String cause(
            String title, String confidence, @Nullable Integer affected, String traces, String sessions) {
        return "{\"title\":\"" + title + "\",\"change\":\"standing\",\"type\":\"prompt\",\"confidence\":\""
                + confidence + "\",\"what_happens\":\"w\",\"how_it_caused_this\":\"h\",\"next_step\":\"n\","
                + (affected == null ? "" : "\"affected_count\":" + affected + ",")
                + "\"evidence_trace_ids\":[" + traces + "],"
                + "\"evidence_session_ids\":[" + sessions + "],"
                + "\"attribution\":{\"path\":\"agent/prompt.md\",\"commit\":\"abc123\",\"excerpt\":\"Answer briefly.\"}}";
    }

    private static String reply(String verdict, String... causes) {
        return replyWithCoverage(
                verdict, "{\"read\":[{\"role\":\"witness\",\"count\":3}],\"how_chosen\":\"all\"}", causes);
    }

    private static String replyWithCoverage(String verdict, String coverage, String... causes) {
        return "{\"summary\":\"s\",\"verdict\":\"" + verdict + "\",\"detailed_report\":\"## r\",\"ruled_out\":[],"
                + "\"coverage\":" + coverage + ",\"causes\":[" + String.join(",", causes) + "]}";
    }

    private static String replyWithoutCoverage(String verdict, String... causes) {
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
                        2,
                        3)),
                out.causes());
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
                        1,
                        3)),
                out.causes());
        assertEquals(List.of(), out.ruledOut());
    }

    /**
     * Citing only comparison rows proves nothing about the flagged ones. The bugs are such a cause surviving, and a
     * comparison id counted toward {@code affected_count}; the comparison receipt itself stays on a kept cause.
     */
    @Test
    void aCauseCitingOnlyComparisonRowsIsDropped() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("A", "high", null, "\"tb-1\"", ""),
                cause("B", "medium", null, "\"tr-1\",\"tb-1\"", "")));

        assertEquals(List.of("B"), titles(out));
        Cause b = out.causes().get(0);
        assertEquals(List.of("tr-1", "tb-1"), b.evidenceTraceIds());
        assertEquals(1, b.affectedCount());
        assertEquals(List.of(), out.platformNotes());

        RcaSynthesisOutput.Parsed none = parse(reply("causes_identified", cause("A", "high", null, "\"tb-1\"", "")));
        assertEquals(RcaReportRow.Verdict.NO_CAUSE_FOUND, none.verdict());
        assertTrue(none.verdictNote().contains("a flagged trace or session"), none.verdictNote());
    }

    /**
     * A count is stored between the flagged rows a cause cites and the flagged total, and a corrected claim is said
     * where the engineer reads. The bugs are a count above the total stored as claimed, and a correction left silent.
     */
    @Test
    void affectedCountIsClampedToCitedAndTotal() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified",
                cause("A", "high", 1, "\"tr-1\",\"tr-2\"", ""),
                cause("B", "high", 900, "\"tr-1\"", ""),
                cause("C", "medium", null, "\"tr-1\"", "")));

        Map<String, Cause> byTitle =
                out.causes().stream().collect(java.util.stream.Collectors.toMap(Cause::title, c -> c));
        assertEquals(2, byTitle.get("A").affectedCount());
        assertEquals(3, byTitle.get("B").affectedCount());
        assertEquals(1, byTitle.get("C").affectedCount());
        out.causes().forEach(c -> assertEquals(3, c.affectedOf(), c.title()));
        assertEquals(2, out.platformNotes().size(), String.valueOf(out.platformNotes()));
        assertTrue(
                out.platformNotes().stream()
                        .anyMatch(n -> n.contains("\"A\"") && n.contains("claimed 1 ") && n.contains("recorded as 2")),
                String.valueOf(out.platformNotes()));
        assertTrue(
                out.platformNotes().stream()
                        .anyMatch(
                                n -> n.contains("\"B\"") && n.contains("claimed 900 ") && n.contains("recorded as 3")),
                String.valueOf(out.platformNotes()));
        assertTrue(out.platformNotes().stream().noneMatch(n -> n.contains("\"C\"")));
    }

    /** Causes may overlap. The bug is a dedupe across causes dropping or zeroing the second one to cite a row. */
    @Test
    void aRowMayCountUnderTwoCauses() {
        RcaSynthesisOutput.Parsed out = parse(reply(
                "causes_identified", cause("A", "high", 1, "\"tr-1\"", ""), cause("B", "high", 1, "\"tr-1\"", "")));

        assertEquals(List.of("A", "B"), titles(out));
        out.causes().forEach(c -> assertEquals(1, c.affectedCount(), c.title()));
        assertEquals(List.of(), out.platformNotes());
    }

    /** The bug is a deploy, config or infrastructure cause stored as {@code other}. */
    @ParameterizedTest
    @ValueSource(strings = {"deploy", "config", "infra"})
    void deployConfigInfraAreKeptNotOther(String type) {
        String typed =
                cause("Typed", "high", 1, "\"tr-1\"", "").replace("\"type\":\"prompt\"", "\"type\":\"" + type + "\"");

        assertEquals(
                type, parse(reply("causes_identified", typed)).causes().get(0).type());
    }

    /**
     * What the analysis says it read is held to what was recorded. The bugs are an over-count stored as read and an
     * unknown role reaching the page.
     */
    @Test
    void coverageCountsAreClamped() {
        RcaSynthesisOutput.Parsed out = parse(replyWithCoverage(
                "causes_identified",
                "{\"read\":[{\"role\":\"witness\",\"count\":70},{\"role\":\"baseline\",\"count\":1},"
                        + "{\"role\":\"changepoint\",\"count\":-2},{\"role\":\"bogus\",\"count\":5}],"
                        + "\"how_chosen\":\"every witness row, baseline paged whole\"}",
                cause("A", "high", 1, "\"tr-1\"", "")));

        assertEquals(
                new Coverage(
                        List.of(
                                new RoleCount("witness", 3),
                                new RoleCount("baseline", 1),
                                new RoleCount("changepoint", 0)),
                        "every witness row, baseline paged whole"),
                out.coverage());
    }

    /** The bug is a reply that ignores the schema's coverage block sinking a finished run. */
    @Test
    void aMissingCoverageBlockReadsAsNone() {
        RcaSynthesisOutput.Parsed out =
                parse(replyWithoutCoverage("causes_identified", cause("A", "high", 1, "\"tr-1\"", "")));

        assertNull(out.coverage());
        assertEquals(List.of("A"), titles(out));
    }

    /**
     * In session grain a flagged row is a session, and the cited traces are its turns. The bug is counting cited
     * turns as flagged rows, which lifts the floor above the flagged total.
     */
    @Test
    void theLowerBoundCountsRowsOfTheGrain() {
        RcaSynthesisOutput.Parsed low = RcaSynthesisOutput.parse(
                MAPPER,
                reply("causes_identified", cause("A", "high", 1, "\"tr-1\",\"tr-2\",\"tr-3\"", "\"s-1\"")),
                SESSION_GRAIN,
                true,
                "proj");
        assertEquals(1, low.causes().get(0).affectedCount());
        assertEquals(List.of(), low.platformNotes());

        RcaSynthesisOutput.Parsed high = RcaSynthesisOutput.parse(
                MAPPER,
                reply("causes_identified", cause("A", "high", 5, "\"tr-1\",\"tr-2\",\"tr-3\"", "\"s-1\"")),
                SESSION_GRAIN,
                true,
                "proj");
        assertEquals(2, high.causes().get(0).affectedCount());
        assertEquals(1, high.platformNotes().size());

        RcaSynthesisOutput.Parsed turnsOnly = RcaSynthesisOutput.parse(
                MAPPER,
                reply("causes_identified", cause("A", "high", null, "\"tr-1\"", "")),
                SESSION_GRAIN,
                true,
                "proj");
        assertEquals(1, turnsOnly.causes().get(0).affectedCount());
    }
}
