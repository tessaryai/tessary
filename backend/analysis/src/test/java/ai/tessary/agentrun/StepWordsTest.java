// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"cat /home/user/dossier/finding.md\"}",
                        "Read the finding and its numbers",
                        "Reading the finding and its numbers"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"cd /home/user && head -n 40 repo/src/index/build.py\"}",
                        "Read src/index/build.py",
                        "Reading src/index/build.py"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"tail -20 dossier/method.md\"}",
                        "Read how the classifier measures",
                        "Reading how the classifier measures"),
                Arguments.of(
                        null, "bash", "{\"command\":\"less repo/README.md\"}", "Read README.md", "Reading README.md"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"sed -n '120,180p' /home/user/repo/src/app.py\"}",
                        "Read src/app.py",
                        "Reading src/app.py"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"jq '.rows[0] | keys' dossier/evidence/rows.json | head\"}",
                        "Read dossier/evidence/rows.json",
                        "Reading dossier/evidence/rows.json"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"sed -i 's/a/b/' fix.py\"}",
                        "Ran sed -i 's/a/b/' fix.py",
                        "Running sed -i 's/a/b/' fix.py"),
                Arguments.of(null, "bash", "{\"command\":\"head -n 5\"}", "Ran head -n 5", "Running head -n 5"),
                Arguments.of(
                        null, "bash", "{\"command\":\"ls -la /home/user\"}", "Listed the files", "Listing the files"),
                Arguments.of(null, "bash", "{\"command\":\"ls repo/src\"}", "Listed src", "Listing src"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"find /home/user/repo -name '*.py'\"}",
                        "Listed the repository",
                        "Listing the repository"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"grep -rn \\\"load_corpus\\\" repo/src\"}",
                        "Searched the repository for \"load_corpus\"",
                        "Searching the repository for \"load_corpus\""),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"rg -n -A 3 -e 'retry|backoff' /home/user/dossier\"}",
                        "Searched the dossier for \"retry|backoff\"",
                        "Searching the dossier for \"retry|backoff\""),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"wc -l dossier/evidence/rows.json\"}",
                        "Counted the lines in dossier/evidence/rows.json",
                        "Counting the lines in dossier/evidence/rows.json"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"python3 -c print(1)\"}",
                        "Ran python3 -c print(1)",
                        "Running python3 -c print(1)"),
                Arguments.of(null, "bash", "{\"command\":\"git show HEAD\"}", "Read a commit", "Reading a commit"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"git diff main\"}",
                        "Compared two versions",
                        "Comparing two versions"),
                Arguments.of(null, "bash", "{\"command\":\"git status\"}", "Ran git status", "Running git status"),
                Arguments.of(
                        null,
                        "bash",
                        "{\"command\":\"cd /home/user/repo && awk -F, '{print $2}' /home/user/dossier/rows.csv | sort | uniq -c\\nhead x\"}",
                        "Ran awk -F, '{print $2}' dossier/rows.csv | sort | uniq -c",
                        "Running awk -F, '{print $2}' dossier/rows.csv | sort | uniq -c"),
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
                        "get_span",
                        "{\"span_id\":\"00f067aa0ba902b7\"}",
                        "Opened a span",
                        "Opening a span"),
                Arguments.of(
                        "tessary-evals",
                        "get_conversation",
                        "{\"session_id\":\"s\"}",
                        "Read a conversation",
                        "Reading a conversation"),
                Arguments.of("tessary-evals", "get_session", "{}", "Read a session", "Reading a session"),
                Arguments.of(
                        "tessary-evals",
                        "get_conversation",
                        "{\"id\":\"5cf321b8-8c1d-4f7e-9a51-0e3c2a7b9d10\"}",
                        "Read conversation 5cf321b8",
                        "Reading conversation 5cf321b8"),
                Arguments.of(
                        "tessary-evals",
                        "get_session",
                        "{\"id\":\"sess-42\"}",
                        "Read session sess-42",
                        "Reading session sess-42"),
                Arguments.of(
                        "tessary-evals", "get_case", "{\"id\":\"C-118\"}", "Read case C-118", "Reading case C-118"),
                Arguments.of(
                        "tessary-evals",
                        "get_finding",
                        "{\"id\":\"01JA2B3C4D5E6F7G8H9J0K1M2N\"}",
                        "Read finding 01JA2B3C",
                        "Reading finding 01JA2B3C"),
                Arguments.of("tessary-evals", "get_finding", "{}", "Read a finding", "Reading a finding"),
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
                Arguments.of(null, "webfetch", "{}", "Used webfetch", "Using webfetch"),
                Arguments.of("docs-search", "search_docs", "{}", "Used search_docs", "Using search_docs"));
    }

    /**
     * A step that says "Read a file" or "Ran a command": each tool call is worded from its input, a repo file named
     * without the repo prefix, a check script by its path, a commit by its short sha, a shell read by the file it
     * reads, and a platform read by a short form of the id it asked for.
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
     * A step that says "Ran a command": a command no rule words is shown as the agent wrote its first line, without the
     * leading cd, without the work dir, and cut to a readable length.
     */
    @Test
    void aCommandNoRuleWordsIsShownAsWrittenAndCutShort() {
        String loop =
                "for f in dossier/evidence/*.json; do python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' $f; done";
        StepWords.Words w = words(null, "bash", commandInput(loop));
        assertTrue(w.done().startsWith("Ran for f in dossier/evidence/*.json; do python3"), w.done());
        assertTrue(w.done().endsWith("..."), w.done());
        assertTrue(w.done().length() <= "Ran ".length() + 80, w.done());
        assertEquals("Could not run " + w.done().substring("Ran ".length()), StepWords.failed(w));
    }

    /** A secret in the step list: a command that names a credential is never shown, even a short one. */
    @Test
    void aCommandThatNamesACredentialIsNotShown() {
        for (String command : List.of(
                "curl -H 'Authorization: Bearer abc123' https://x",
                "PGPASSWORD=hunter2 psql -h db",
                "export API_KEY=abc",
                "echo $OPENAI_API_KEY",
                "env | grep -i token",
                "mysql --password=abc")) {
            assertEquals(
                    "Ran a command", words(null, "bash", commandInput(command)).done(), command);
        }
    }

    private static String commandInput(String command) {
        return "{\"command\":" + JsonNodeFactory.instance.textNode(command) + "}";
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
                words("tessary-evals", "get_trace", "{\"trace_id\":\"" + token + "\"}"),
                words("tessary-evals", "get_conversation", "{\"id\":\"" + token + "\"}"),
                words(null, "bash", "{\"command\":\"cat /home/user/" + token + ".txt\"}"));
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
        assertEquals("Read a conversation", all.get(7).done());
        assertEquals("Read a file", all.get(8).done());
    }

    static Stream<Arguments> notes() {
        return Stream.of(
                Arguments.of("I'll start with the finding. Then I will", "I'll start with the finding"),
                Arguments.of("Let me look", null),
                Arguments.of("Version 3.", null),
                Arguments.of("First I read it.\nNow the history is clear!\nNext", "Now the history is clear!"),
                Arguments.of("## Plan\n- **Read** the `finding.md` file\n", "Read the finding.md file"),
                Arguments.of("> See [the guide](https://example.com/guide) first:\n", "See the guide first"),
                Arguments.of("Is the index stale? Checking", "Is the index stale?"),
                Arguments.of("The key tsy_a_" + "Q".repeat(40) + " leaked here. ", "The key leaked here"),
                Arguments.of("Done.\n---\n", "Done"),
                Arguments.of("Thinking...\n", "Thinking"));
    }

    /**
     * A live line that reads "Thinking" while the agent says what it is doing: its own words become a note, the newest
     * whole sentence or line only, in plain text with no markdown and no token, with no closing period for the page's
     * "…" to follow.
     */
    @ParameterizedTest
    @MethodSource("notes")
    void theAgentsLatestWholeSentenceIsItsNote(String text, @Nullable String note) {
        assertEquals(note, StepWords.note(text));
    }

    /** A live line as long as a paragraph: a note is cut at a word, to at most 160 characters. */
    @Test
    void aLongSentenceIsCutAtAWordToAtMost160Characters() {
        String sentence = "word ".repeat(50).strip() + ". ";

        String note = StepWords.note(sentence);

        assertEquals("word ".repeat(32).strip(), note);
    }

    /**
     * The ruling's JSON shown as the live line: text that starts an object or a code fence on any line is the answer
     * being written, while narration that only mentions braces is not.
     */
    @Test
    void textThatStartsAnObjectOrAFenceOnALineIsTheAnswer() {
        assertTrue(StepWords.answerShaped("{\"verdict\":"));
        assertTrue(StepWords.answerShaped("  ```json\n{"));
        assertTrue(StepWords.answerShaped("Here is the ruling.\n\n```json\n"));
        assertFalse(StepWords.answerShaped("Let me look at {\"a\":1} first. "));
        assertFalse(StepWords.answerShaped(""));
    }
}
