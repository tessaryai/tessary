// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierField;
import ai.tessary.classifier.substrate.ConversationThreadRenderer.Turn;
import ai.tessary.config.ClassifierProperties;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the THREAD assembly seam: the session walk renders the user↔assistant dialogue
 * oldest-first with the scored message last, surfaces tool observations as terse outcome markers,
 * EXCLUDES system messages (product decision — we assess the interaction, not the agent's system
 * prompt), and reduces to the character budget with trajectory-preserving eviction (baseline head +
 * recent tail kept, middle elided, scored message never dropped). The session read is mocked; the
 * DB-backed walk is covered by {@link ConversationThreadAssemblerIntegrationTest}.
 */
class ConversationThreadAssemblerTest {

    private static final String SID = "sess-1";

    private ConversationThreadAssembler assembler(ClassifierProperties props, List<SubstrateObservation> sessionDesc) {
        SubstrateReadRepository substrate = mock(SubstrateReadRepository.class);
        // The repository returns newest-first; the assembler reverses to chronological.
        when(substrate.conversationObservationsUpTo(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(sessionDesc);
        return new ConversationThreadAssembler(substrate, props);
    }

    private static ClassifierProperties props(int charBudget) {
        return props(charBudget, 3);
    }

    private static ClassifierProperties props(int charBudget, int recentTurns) {
        ClassifierProperties p = new ClassifierProperties();
        p.setThreadCharBudget(charBudget);
        p.setThreadRecentTurns(recentTurns);
        p.setThreadMaxObservations(40);
        return p;
    }

    /** A dialogue span in a trace of its own — each span is a distinct turn, because the turn IS the trace. */
    private static SubstrateObservation obs(String id, String ts, @Nullable String input, @Nullable String output) {
        return obs(id, ts, "tr-" + id, "llm", input, output);
    }

    /** A dialogue/tool span with an explicit TRACE id and kind (agent/llm twins share a trace = a turn). */
    private static SubstrateObservation obs(
            String id, String ts, String traceId, String kind, @Nullable String input, @Nullable String output) {
        return new SubstrateObservation(id, "p", traceId, SID, null, null, kind, "chat", input, output, null, ts);
    }

    private static SubstrateObservation toolObs(String id, String ts, String name, @Nullable String error) {
        return toolObs(id, ts, "tr-" + id, name, error);
    }

    private static SubstrateObservation toolObs(
            String id, String ts, String traceId, String name, @Nullable String error) {
        return new SubstrateObservation(id, "p", traceId, SID, null, null, "tool", name, null, null, error, ts);
    }

    private static String env(String role, String text) {
        return "[{\"role\":\"" + role + "\",\"content\":\"" + text + "\"}]";
    }

    @Test
    void assemblesMultiTurnDialogueOldestFirstWithScoredMessageLast() {
        SubstrateObservation scored = obs("scored", "t9", env("user", "nevermind"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                scored,
                obs("o2", "t2", env("user", "still broken"), env("assistant", "Let me retry.")),
                obs(
                        "o1",
                        "t1",
                        env("user", "can you export this?"),
                        env("assistant", "Sure, running the export now."))));

        String rendered = assembler(props(10_000), sessionDesc).assemble(scored, ClassifierField.INPUT);

        assertEquals(
                "[user] can you export this?\n"
                        + "[assistant] Sure, running the export now.\n"
                        + "[user] still broken\n"
                        + "[assistant] Let me retry.\n"
                        + "[user] nevermind",
                rendered,
                "prior user/assistant turns oldest-first, scored user message last");
    }

    @Test
    void contextPolicyNarrowsToTheLastExchangeWithAssistantProseStubbed() {
        SubstrateObservation scored = obs("scored", "t9", env("user", "ok fine, noted the ticket number"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                scored,
                obs("o2", "t2", env("user", "policy SHJ-1, name A B"), env("assistant", "Verified. It lapsed 2 July.")),
                obs("o1", "t1", env("user", "is my policy active?"), env("assistant", "Let me check that for you."))));

        String rendered = assembler(props(10_000), sessionDesc)
                .assemble(scored, ClassifierField.INPUT, new ConversationThreadAssembler.ContextPolicy(1, true));

        assertEquals(
                "[user] policy SHJ-1, name A B\n" + "[assistant] [reply]\n" + "[user] ok fine, noted the ticket number",
                rendered,
                "one exchange kept, assistant prose stubbed, earlier turns dropped entirely");
    }

    @Test
    void minPriorUserTurnsSkipsTheOpenerButScoresTheNextTurn() {
        SubstrateObservation opener = obs("o1", "t1", env("user", "hi, is my policy active?"), null);
        String rendered = assembler(props(10_000), new ArrayList<>(List.of(opener)))
                .assemble(opener, ClassifierField.INPUT, new ConversationThreadAssembler.ContextPolicy(1, true, 1));
        assertEquals("", rendered, "a conversation's first user turn has no prior exchange — never scored");

        SubstrateObservation second = obs("o2", "t2", env("user", "ok fine, noted"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                second, obs("o1", "t1", env("user", "is my policy active?"), env("assistant", "It lapsed 2 July."))));
        assertEquals(
                "[user] is my policy active?\n[assistant] [reply]\n[user] ok fine, noted",
                assembler(props(10_000), sessionDesc)
                        .assemble(
                                second,
                                ClassifierField.INPUT,
                                new ConversationThreadAssembler.ContextPolicy(1, true, 1)),
                "the turn AFTER the opener is scored normally");
    }

    @Test
    void minPriorUserTurnsCountsTheUnnarrowedThreadNotTheWindow() {
        // The gate must not move when context_user_turns changes: a turn deep in a conversation is
        // eligible even though the window it will be SHOWN is only one exchange. Were the count taken
        // after narrowing, a window of 1 would cap the count at 1 and silently gate out every turn for
        // any min > 1.
        SubstrateObservation scored = obs("scored", "t9", env("user", "third ask"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                scored,
                obs("o2", "t2", env("user", "second ask"), env("assistant", "second reply")),
                obs("o1", "t1", env("user", "first ask"), env("assistant", "first reply"))));

        assertFalse(
                assembler(props(10_000), sessionDesc)
                        .assemble(
                                scored,
                                ClassifierField.INPUT,
                                new ConversationThreadAssembler.ContextPolicy(1, true, 2))
                        .isEmpty(),
                "2 prior user turns exist in the THREAD, so min=2 admits it even with a 1-exchange window");
    }

    @Test
    void defaultPolicyLeavesTheThreadUntouchedSoUnconfiguredHeadsAreUnchanged() {
        SubstrateObservation scored = obs("scored", "t9", env("user", "nevermind"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                scored,
                obs("o2", "t2", env("user", "still broken"), env("assistant", "Let me retry.")),
                obs("o1", "t1", env("user", "can you export this?"), env("assistant", "Sure, running it now."))));

        ConversationThreadAssembler a = assembler(props(10_000), sessionDesc);
        assertEquals(
                a.assemble(scored, ClassifierField.INPUT),
                a.assemble(scored, ClassifierField.INPUT, ConversationThreadAssembler.ContextPolicy.FULL),
                "FULL is exactly the pre-policy behaviour");
    }

    @Test
    void excludesSystemMessagesFromTheThread() {
        String withSystem = "[{\"role\":\"system\",\"content\":\"You are a helpful assistant.\"},"
                + "{\"role\":\"user\",\"content\":\"export this\"}]";
        SubstrateObservation scored = obs("scored", "t9", env("user", "nevermind"), null);
        List<SubstrateObservation> sessionDesc =
                new ArrayList<>(List.of(scored, obs("o1", "t1", withSystem, env("assistant", "Sure."))));

        String rendered = assembler(props(10_000), sessionDesc).assemble(scored, ClassifierField.INPUT);

        assertFalse(rendered.contains("helpful assistant"), "the system prompt is never rendered");
        assertFalse(rendered.contains("[system]"), "no system speaker block is emitted");
        assertEquals("[user] export this\n[assistant] Sure.\n[user] nevermind", rendered);
    }

    @Test
    void outputFieldEndsThreadAtScoredAssistantMessage() {
        SubstrateObservation scored = obs("scored", "t9", env("user", "run it"), env("assistant", "here is your key"));
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(scored));

        String rendered = assembler(props(10_000), sessionDesc).assemble(scored, ClassifierField.OUTPUT);

        assertEquals(
                "[user] run it\n[assistant] here is your key",
                rendered,
                "OUTPUT carries the scored user turn as context and ends at the scored assistant message");
    }

    @Test
    void multimodalNonTextPartsRenderAsPlaceholders() {
        String withImage =
                "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"look\"}," + "{\"type\":\"image\"}]}]";
        SubstrateObservation scored = obs("scored", "t9", withImage, null);
        String rendered =
                assembler(props(10_000), new ArrayList<>(List.of(scored))).assemble(scored, ClassifierField.INPUT);
        assertEquals("look [image]", rendered, "an image part is represented, not dropped");
    }

    @Test
    void toolObservationsSurfaceAsTerseOutcomeMarkers() {
        SubstrateObservation scored = obs("scored", "t9", env("user", "nevermind, I'll do it myself"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                scored,
                toolObs("tErr", "t3", "deploy", "exit 1: connection refused"),
                toolObs("tOk", "t2", "search", null),
                obs("o1", "t1", env("user", "deploy the app"), env("assistant", "On it."))));

        String rendered = assembler(props(10_000), sessionDesc).assemble(scored, ClassifierField.INPUT);

        assertEquals(
                "[user] deploy the app\n"
                        + "[assistant] On it.\n"
                        + "[tool:search ok]\n"
                        + "[tool:deploy error: exit 1: connection refused]\n"
                        + "[user] nevermind, I'll do it myself",
                rendered,
                "tool spans surface as terse ok/error markers, not raw payloads, in trajectory order");
    }

    @Test
    void budgetEvictionKeepsFailureHeadAndRecentTailAndMarksElision() {
        // A long agent trajectory: an early tool FAILURE (the antecedent), a redundant middle, and a
        // quiet-frustration final. The reduction keeps the failure head + recent tail and marks the gap.
        SubstrateObservation scored = obs("scored", "t9", env("user", "nevermind, I'll just do it by hand"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                scored,
                obs("o5", "t7", env("user", "and the orders table?"), env("assistant", "Migrating orders now.")),
                obs("o4", "t6", env("user", "ok keep going"), env("assistant", "Users table migrated.")),
                obs("o3", "t5", env("user", "retry it"), env("assistant", "Retrying the migration.")),
                toolObs("tErr", "t4", "migrate", "lock timeout: could not acquire ACCESS EXCLUSIVE on users"),
                obs("o1", "t1", env("user", "migrate the users table"), env("assistant", "Starting the migration."))));

        String rendered = assembler(props(200, 2), sessionDesc).assemble(scored, ClassifierField.INPUT);

        assertTrue(rendered.startsWith("[user] migrate the users table"), "the baseline head (earliest turn) is kept");
        assertTrue(
                rendered.contains("[tool:migrate error: lock timeout"),
                "the earliest failure marker is kept as the antecedent");
        assertTrue(rendered.contains("earlier turns elided"), "the elided middle is marked, never silent");
        assertTrue(
                rendered.endsWith("[user] nevermind, I'll just do it by hand"),
                "the scored final message is never dropped");
    }

    @Test
    void collapsesAgentAndLlmTwinsToOneDialoguePerTurnKeepingToolMarkers() {
        // Each turn carries BOTH an agent and an llm span bearing the same delta message. The thread
        // must show that turn's message ONCE (the llm span is preferred), not twice — while the turn's
        // tool span still surfaces as its own outcome marker (evidence, not a duplicate). The scored
        // turn's agent twin must not re-emit the trailing turn either.
        SubstrateObservation scoredAgent = obs("s-agent", "t8", "turnZ", "agent", env("user", "nevermind"), null);
        SubstrateObservation scored = obs("scored", "t9", "turnZ", "llm", env("user", "nevermind"), null);
        List<SubstrateObservation> sessionDesc = new ArrayList<>(List.of(
                scored,
                scoredAgent,
                toolObs("tool1", "t3", "turnA", "search", null),
                obs("o1-llm", "t2", "turnA", "llm", env("user", "deploy the app"), env("assistant", "On it.")),
                obs("o1-agent", "t1", "turnA", "agent", env("user", "deploy the app"), env("assistant", "On it."))));

        String rendered = assembler(props(10_000), sessionDesc).assemble(scored, ClassifierField.INPUT);

        assertEquals(
                "[user] deploy the app\n[assistant] On it.\n[tool:search ok]\n[user] nevermind",
                rendered,
                "the agent/llm twins collapse to one dialogue per turn; the tool marker still surfaces");
        assertEquals(
                1,
                countOccurrences(rendered, "deploy the app"),
                "the turn's user message appears exactly once, not once per agent/llm span");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    @Test
    void singleTurnDegradesToBareScoredMessage() {
        SubstrateObservation scored = obs("scored", "t9", env("user", "the export button returns a 403"), null);
        String rendered =
                assembler(props(10_000), new ArrayList<>(List.of(scored))).assemble(scored, ClassifierField.INPUT);
        assertEquals("the export button returns a 403", rendered, "no prior context → bare message, no [user] prefix");
    }

    @Test
    void reducerKeepsOversizeSingleMessageIntact() {
        String big = "x".repeat(200);
        String rendered = ConversationThreadRenderer.reduceThread(List.of(), Turn.user(big), 50, 3);
        assertEquals(big, rendered, "an over-budget single message is kept intact (serving-side truncates)");
    }

    @Test
    void reductionKeepsScoredMessageLastWhenTrimming() {
        List<Turn> context =
                List.of(Turn.user("OLDEST"), Turn.assistant("mid one"), Turn.assistant("mid two"), Turn.user("NEWEST"));
        String rendered = ConversationThreadRenderer.reduceThread(context, Turn.user("final"), 40, 1);
        assertTrue(rendered.startsWith("[user] OLDEST"), "the baseline head is kept");
        assertTrue(rendered.contains("earlier turns elided"), "the trimmed middle is marked");
        assertTrue(rendered.endsWith("[user] final"), "scored message stays last");
    }
}
