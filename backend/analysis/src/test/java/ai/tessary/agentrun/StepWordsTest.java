// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The plain words a triage or RCA page shows for each tool call the agent makes. */
class StepWordsTest {

    private static final StepWords.Context GROUNDEDNESS = new StepWords.Context(
            Map.of("witness", "flagged answers", "baseline", "the reference window"), Map.of("witness", 58L));

    private static SessionEvent.ToolStart call(@Nullable String server, String tool, String input) {
        return new SessionEvent.ToolStart(1, "c-1", server, tool, input);
    }

    private static StepWords.Words words(@Nullable String server, String tool, String input) {
        StepWords.Words w = StepWords.of(call(server, tool, input), GROUNDEDNESS);
        if (w == null) throw new AssertionError(tool + " was silent");
        return w;
    }

    static Stream<Arguments> table() {
        return Stream.of(
                Arguments.of(
                        null,
                        "read",
                        "{\"filePath\":\"/home/user/dossier/finding.md\"}",
                        "Read the finding and its numbers",
                        "Reading the finding and its numbers"),
                Arguments.of(
                        null,
                        "read",
                        "{\"filePath\":\"/work/dossier/method.md\"}",
                        "Read how the classifier measures",
                        "Reading how the classifier measures"),
                Arguments.of(
                        null,
                        "read",
                        "{\"filePath\":\"/home/user/dossier/tools.md\"}",
                        "Read the tool guide",
                        "Reading the tool guide"),
                Arguments.of(
                        null,
                        "read",
                        "{\"filePath\":\"/home/user/dossier/evidence/rows.json\"}",
                        "Read dossier/evidence/rows.json",
                        "Reading dossier/evidence/rows.json"),
                Arguments.of(
                        null,
                        "write",
                        "{\"filePath\":\"/home/user/checks/compare.py\",\"content\":\"print(1)\"}",
                        "Wrote checks/compare.py",
                        "Writing checks/compare.py"),
                Arguments.of(
                        null,
                        "edit",
                        "{\"filePath\":\"/work/checks/compare.py\",\"oldString\":\"a\",\"newString\":\"b\"}",
                        "Wrote checks/compare.py",
                        "Writing checks/compare.py"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"git log --oneline -20\",\"description\":\"history\"}",
                        "Read the commit history",
                        "Reading the commit history"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"cd /home/user/repo && git show a41c0de9f2b1 --stat\"}",
                        "Read commit a41c0de",
                        "Reading commit a41c0de"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"git -C repo diff 1234567890 abcdef1234\"}",
                        "Compared 1234567 and abcdef1",
                        "Comparing 1234567 and abcdef1"),
                Arguments.of(null, "bash", "{\"command\":\"ls -la /home/user\"}", "Ran a command", "Running a command"),
                Arguments.of(
                        null,
                        "grep",
                        "{\"pattern\":\"retriever\",\"path\":\"/home/user/repo/src\"}",
                        "Searched the repository for \"retriever\"",
                        "Searching the repository for \"retriever\""),
                Arguments.of(
                        null,
                        "grep",
                        "{\"pattern\":\"index_documents|rebuild_index|load_corpus\",\"path\":\"/home/user/dossier\"}",
                        "Searched the dossier for \"index_documents|rebuild_index|load_corpu\"",
                        "Searching the dossier for \"index_documents|rebuild_index|load_corpu\""),
                Arguments.of(
                        null,
                        "grep",
                        "{\"pattern\":\"TODO\"}",
                        "Searched the files for \"TODO\"",
                        "Searching the files for \"TODO\""),
                Arguments.of(
                        null,
                        "glob",
                        "{\"pattern\":\"**/*.py\"}",
                        "Listed the files matching **/*.py",
                        "Listing the files matching **/*.py"),
                Arguments.of(null, "list", "{\"path\":\"/home/user/repo/src\"}", "Listed src", "Listing src"),
                Arguments.of(
                        "tessary-evals",
                        "get_finding_evidence",
                        "{\"finding_id\":\"f1\",\"count_only\":true}",
                        "Counted the evidence",
                        "Counting the evidence"),
                Arguments.of(
                        "tessary-evals",
                        "get_finding_evidence",
                        "{\"finding_id\":\"f1\",\"role\":\"baseline\",\"limit\":20}",
                        "Read the reference window",
                        "Reading the reference window"),
                Arguments.of(
                        "tessary-evals",
                        "get_trace",
                        "{\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\"}",
                        "Opened trace 4bf92f3577b34da6",
                        "Opening trace 4bf92f3577b34da6"),
                Arguments.of(
                        "tessary-evals",
                        "get_span",
                        "{\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"span_id\":\"00f067aa0ba902b7\"}",
                        "Opened a span of trace 4bf92f3577b34da6",
                        "Opening a span of trace 4bf92f3577b34da6"),
                Arguments.of(
                        "tessary-evals",
                        "get_conversation",
                        "{\"session_id\":\"s\"}",
                        "Read a conversation",
                        "Reading a conversation"),
                Arguments.of("tessary-evals", "get_session", "{}", "Read a session", "Reading a session"),
                Arguments.of("tessary-evals", "list_traces", "{}", "Listed traces", "Listing traces"),
                Arguments.of(
                        "tessary-evals",
                        "query_count",
                        "{}",
                        "Counted the matching traces",
                        "Counting the matching traces"),
                Arguments.of("tessary-evals", "list_findings", "{}", "Listed the findings", "Listing the findings"),
                Arguments.of("tessary-evals", "get_case", "{}", "Read a case", "Reading a case"),
                Arguments.of("tessary-evals", "brand_new_tool", "{}", "Used brand_new_tool", "Using brand_new_tool"),
                Arguments.of(null, "webfetch", "{}", "Used webfetch", "Using webfetch"));
    }

    /**
     * A step that says "Read a file": each tool call is worded from its input, a repo file named without the repo
     * prefix, a check script by its path, a commit by its short sha.
     */
    @ParameterizedTest
    @MethodSource("table")
    void eachToolCallIsWordedFromItsInput(
            @Nullable String server, String tool, String input, String done, String doing) {
        StepWords.Words w = words(server, tool, input);
        assertEquals(List.of(done, doing), List.of(w.done(), w.doing()));
    }

    /** A step that says "Read a file": a read of a repo file names the file without the repo prefix. */
    @Test
    void aReadOfARepoFileNamesTheFileWithoutTheRepoPrefix() {
        StepWords.Words w = words(null, "read", "{\"filePath\":\"/work/repo/src/index/build.py\",\"offset\":40}");
        assertEquals(
                new StepWords.Words("Read src/index/build.py", "Reading src/index/build.py", "read src/index/build.py"),
                w);
        assertEquals("Could not read src/index/build.py", StepWords.failed(w));
    }

    /** A step that hides which check ran: a bash run of a check script names the script. */
    @Test
    void aBashRunOfACheckScriptNamesTheScript() {
        assertEquals(
                new StepWords.Words("Ran checks/compare.py", "Running checks/compare.py", "run checks/compare.py"),
                words(null, "bash", "{\"command\":\"python3 -u /home/user/checks/compare.py --rows 50\"}"));
        assertEquals(
                "Ran checks/scan.js",
                words(null, "bash", "{\"command\":\"node checks/scan.js\"}").done());
    }

    /**
     * A range past the evidence: evidence pages count the rows read so far across calls, advance only when a call
     * ends well, and stop at the role's total.
     */
    @Test
    void evidencePagesCountRowsAcrossCallsAndStopAtTheTotal() {
        StepWords.RowCursor cursor = new StepWords.RowCursor();
        StepWords.Words first = StepWords.of(evidence("c-1", 50), GROUNDEDNESS, cursor);
        cursor.ended("c-1", true);
        StepWords.Words failed = StepWords.of(evidence("c-2", 50), GROUNDEDNESS, cursor);
        cursor.ended("c-2", false);
        StepWords.Words second = StepWords.of(evidence("c-3", 50), GROUNDEDNESS, cursor);
        cursor.ended("c-3", true);
        StepWords.Words past = StepWords.of(evidence("c-4", 50), GROUNDEDNESS, cursor);
        StepWords.Words unpaged = StepWords.of(
                new SessionEvent.ToolStart(5, "c-5", "tessary-evals", "get_finding_evidence", "{\"role\":\"member\"}"),
                GROUNDEDNESS,
                cursor);

        assertEquals(
                new StepWords.Words(
                        "Read flagged answers 1 to 50 of 58",
                        "Reading flagged answers 1 to 50",
                        "read flagged answers 1 to 50"),
                first);
        assertEquals("Reading flagged answers 51 to 58", failed == null ? null : failed.doing());
        assertEquals(
                new StepWords.Words(
                        "Read flagged answers 51 to 58 of 58",
                        "Reading flagged answers 51 to 58",
                        "read flagged answers 51 to 58"),
                second);
        assertEquals(
                new StepWords.Words("Read flagged answers", "Reading flagged answers", "read flagged answers"), past);
        assertEquals(
                new StepWords.Words(
                        "Read the flagged population 1 to 100",
                        "Reading the flagged population 1 to 100",
                        "read the flagged population 1 to 100"),
                unpaged,
                "a role with no noun or total reads with the default page and no total");
    }

    private static SessionEvent.ToolStart evidence(String callId, int limit) {
        return new SessionEvent.ToolStart(
                1,
                callId,
                "tessary-evals",
                "get_finding_evidence",
                "{\"finding_id\":\"f1\",\"role\":\"witness\",\"limit\":" + limit + "}");
    }

    /** A step line that throws on long input: a cut or unparseable input falls back to the generic words. */
    @Test
    void aCutOrUnparseableInputFallsBackToTheGenericWords() {
        String cut = "{\"filePath\":\"/home/user/repo/src/a.py\",\"content\":\"" + "x".repeat(1990);
        assertEquals(new StepWords.Words("Wrote a file", "Writing a file", "write a file"), words(null, "write", cut));
        assertEquals("Read a file", words(null, "read", "not json").done());
        assertEquals("Ran a command", words(null, "bash", "{\"command\":42}").done());
        assertEquals(
                "Read the evidence",
                words("tessary-evals", "get_finding_evidence", "{\"role\":").done());
        assertEquals("Opened a trace", words("tessary-evals", "get_trace", "[]").done());
    }

    /** Steps that are only bookkeeping: todowrite and todoread are silent. */
    @Test
    void todowriteIsSilent() {
        assertNull(StepWords.of(call(null, "todowrite", "{\"todos\":[]}"), GROUNDEDNESS));
        assertNull(StepWords.of(call(null, "todoread", "{}"), GROUNDEDNESS));
    }

    /**
     * Secrets in the step list: no step words carry tool output or a token-shaped string, even when the agent put one
     * in a path, a pattern or a command.
     */
    @Test
    void noStepWordsContainToolOutputOrATokenShapedString() {
        String token = "tsy_a_" + "Q".repeat(40);
        List<StepWords.Words> all = List.of(
                words(null, "read", "{\"filePath\":\"/home/user/" + token + ".txt\"}"),
                words(null, "grep", "{\"pattern\":\"" + token + "\"}"),
                words(null, "grep", "{\"pattern\":\"sk-ant-api03-abcdefghij\"}"),
                words(null, "bash", "{\"command\":\"curl -H 'Authorization: Bearer " + token + "' x\"}"),
                words(null, "bash", "{\"command\":\"python3 /home/user/checks/" + token + ".py\"}"),
                words(null, "glob", "{\"pattern\":\"" + "A1b2C3d4".repeat(6) + "\"}"),
                words("tessary-evals", "get_trace", "{\"trace_id\":\"" + token + "\"}"));
        Pattern tokenShaped = Pattern.compile("tsy_|sk-|[A-Za-z0-9_\\-]{32,}");
        for (StepWords.Words w : all) {
            for (String s : List.of(w.done(), w.doing(), w.could())) {
                assertFalse(tokenShaped.matcher(s).find(), s);
            }
        }
        assertEquals("Read a file", all.get(0).done());
        assertEquals("Searched the files", all.get(1).done());
        assertEquals("Ran a command", all.get(4).done());
        assertEquals("Opened a trace", all.get(6).done());
    }
}
