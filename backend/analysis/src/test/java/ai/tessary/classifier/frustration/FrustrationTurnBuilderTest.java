// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.frustration.FrustrationTurnBuilder.Caps;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.EarlierMessage;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.TurnState;
import ai.tessary.classifier.frustration.StructuredThread.Message;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Eligibility, caps, paste markers and the budget over a real {@link ConversationThreadAssembler} with
 * the conversation read mocked. The five-message fixture is the research formatter's demo thread.
 */
class FrustrationTurnBuilderTest {

    private static final String DEMO_ASSISTANT_1 =
            "Sure! Here you go:\n```python\ndef parse(line):\n    return line.split()\n```\n"
                    + "Let me know if you want error handling.";
    private static final String DEMO_ASSISTANT_2 = "Apologies. " + "Try this instead. ".repeat(80);

    // ---- fixtures

    /** A conversation's spans, oldest first; the last one is the scored turn. */
    private static Optional<TurnState> build(SubstrateObservation... chronological) {
        return build(Caps.DEFAULT, chronological);
    }

    private static Optional<TurnState> build(Caps caps, SubstrateObservation... chronological) {
        List<SubstrateObservation> earlier = List.of(chronological).subList(0, chronological.length - 1);
        int turns = (int)
                earlier.stream().map(SubstrateObservation::traceId).distinct().count();
        SubstrateReadRepository substrate = mock(SubstrateReadRepository.class);
        when(substrate.priorTurns(anyString(), anyString(), anyInt()))
                .thenReturn(new SubstrateReadRepository.PriorTurns(earlier, turns));
        return new FrustrationTurnBuilder(new ConversationThreadAssembler(substrate), caps)
                .build(chronological[chronological.length - 1]);
    }

    private static SubstrateObservation turn(String id, @Nullable String input, @Nullable String output) {
        return span(id, "tr-" + id, "llm", input, output);
    }

    private static SubstrateObservation span(
            String id, String traceId, String kind, @Nullable String input, @Nullable String output) {
        return new SubstrateObservation(
                id, "p", traceId, "sess-1", null, "cs-1", kind, "chat", input, output, null, id);
    }

    private static String say(String role, String text) {
        return "[{\"role\":\"" + role + "\",\"content\":" + json(text) + "}]";
    }

    private static String json(String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Two full exchanges and a third user message: the first eligible shape. */
    private static SubstrateObservation[] threeTurns(String assistant1, String assistant2, String current) {
        return new SubstrateObservation[] {
            turn("t1", say("user", "first question"), assistant1),
            turn("t2", say("user", "second question"), assistant2),
            turn("t3", say("user", current), null)
        };
    }

    private static Message text(String role, String text) {
        return new Message(role, text, true, false);
    }

    // ---- eligibility

    @Test
    void build_sendsTheThirdUserTurnWithFourPriorMessagesOldestFirst() {
        TurnState state = build(threeTurns(say("assistant", "one"), say("assistant", "two"), "still wrong"))
                .orElseThrow();
        assertEquals("still wrong", state.currentUserMessage());
        assertEquals(
                List.of(
                        new EarlierMessage("user", "first question"),
                        new EarlierMessage("assistant", "one"),
                        new EarlierMessage("user", "second question"),
                        new EarlierMessage("assistant", "two")),
                state.earlierMessages());
    }

    @Test
    void build_neverSendsTheOpener() {
        assertTrue(build(turn("t1", say("user", "hello"), null)).isEmpty());
    }

    @Test
    void build_neverSendsTheSecondUserTurn() {
        assertTrue(
                build(turn("t1", say("user", "hello"), say("assistant", "hi")), turn("t2", say("user", "and?"), null))
                        .isEmpty());
    }

    @ParameterizedTest(name = "slot {0} blank")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void format_refusesATurnWithAnyOfItsFiveSlotsBlank(int blankSlot) {
        List<Message> slots = new ArrayList<>(List.of(
                text("user", "a"),
                text("assistant", "b"),
                text("user", "c"),
                text("assistant", "d"),
                text("user", "e")));
        Message blank = slots.get(blankSlot);
        slots.set(blankSlot, new Message(blank.role(), "", false, false));
        StructuredThread thread = new StructuredThread(slots.subList(0, 4), slots.get(4), 3);
        assertTrue(FrustrationTurnBuilder.format(thread, Caps.DEFAULT).isEmpty(), "slot " + blankSlot);
    }

    @Test
    void build_refusesATurnWhoseLatestAssistantEndedOnAToolCall() {
        String toolOnly = "[{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\"}}]}]";
        assertTrue(
                build(threeTurns(say("assistant", "one"), toolOnly, "hello?")).isEmpty());
    }

    @Test
    void build_refusesATurnWhoseAssistantTextIsFollowedByAToolCall() {
        String textThenTool = "[{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Checking.\"},"
                + "{\"type\":\"tool_use\",\"name\":\"lookup\",\"input\":{}}]}]";
        assertTrue(
                build(threeTurns(textThenTool, say("assistant", "two"), "hello?"))
                        .isEmpty(),
                "the older assistant turn also counts");
    }

    @Test
    void build_joinsAssistantTextSplitAroundAToolCallInOrder() {
        String split = "[{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Checking.\"},"
                + "{\"type\":\"tool_use\",\"name\":\"lookup\",\"input\":{}},"
                + "{\"type\":\"text\",\"text\":\"Found it.\"}]}]";
        TurnState state =
                build(threeTurns(say("assistant", "one"), split, "thanks")).orElseThrow();
        assertEquals("Checking. Found it.", state.earlierMessages().get(3).content(), "the tool part leaves no text");
    }

    @Test
    void build_joinsEveryAssistantSpanOfAnAgenticTurn() {
        String toolOnly = "[{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\"}}]}]";
        TurnState state = build(
                        turn("t1", say("user", "first question"), say("assistant", "one")),
                        span("t2a", "tr-t2", "agent", say("user", "second question"), say("assistant", "Done.")),
                        span("t2b", "tr-t2", "llm", say("user", "second question"), toolOnly),
                        span("t2c", "tr-t2", "tool", null, null),
                        span(
                                "t2d",
                                "tr-t2",
                                "llm",
                                "[{\"role\":\"tool\",\"content\":\"42\"}]",
                                say("assistant", "Done.")),
                        turn("t3", say("user", "ok"), null))
                .orElseThrow();
        assertEquals(
                new EarlierMessage("assistant", "Done."),
                state.earlierMessages().get(3),
                "the final answer of the turn, once, with the agent twin and the tool span dropped");
        assertEquals(
                new EarlierMessage("user", "second question"),
                state.earlierMessages().get(2));
    }

    @Test
    void build_refusesAnImagesOnlyAssistantTurn() {
        String imageOnly =
                "[{\"role\":\"assistant\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}]";
        assertTrue(build(threeTurns(say("assistant", "one"), imageOnly, "where is the text?"))
                .isEmpty());
    }

    @Test
    void build_sendsAnImageBesideTextAsItsPlaceholder() {
        String captioned = "[{\"role\":\"assistant\",\"content\":[{\"type\":\"image\",\"caption\":\"chart\"},"
                + "{\"type\":\"text\",\"text\":\"Here is the chart.\"}]}]";
        TurnState state = build(threeTurns(say("assistant", "one"), captioned, "wrong chart"))
                .orElseThrow();
        assertEquals(
                "[image: chart] Here is the chart.",
                state.earlierMessages().get(3).content());
    }

    @Test
    void build_dropsReasoningTheUserNeverSaw() {
        String withReasoning = "[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"hmm\","
                + "\"text\":\"secret plan\"},{\"type\":\"text\",\"text\":\"Sure.\"}]}]";
        TurnState state =
                build(threeTurns(say("assistant", "one"), withReasoning, "no")).orElseThrow();
        assertEquals("Sure.", state.earlierMessages().get(3).content());
    }

    @Test
    void build_refusesAUserWhoWroteTwiceWithNoReplyBetween() {
        assertTrue(build(
                        turn("t1", say("user", "first"), say("assistant", "one")),
                        turn("t2", say("user", "second"), say("assistant", "two")),
                        turn("t3", say("user", "third"), null),
                        turn("t4", say("user", "hello??"), null))
                .isEmpty());
    }

    // ---- the research demo thread, pastes and caps

    @Test
    void build_rendersTheResearchDemoThread() {
        TurnState state = build(threeTurnsOf(
                        "write a python function to parse this log",
                        DEMO_ASSISTANT_1,
                        "it crashes on empty lines",
                        DEMO_ASSISTANT_2,
                        "I ALREADY told you the input is tab separated!!"))
                .orElseThrow();
        assertEquals("I ALREADY told you the input is tab separated!!", state.currentUserMessage());
        assertEquals(
                List.of(
                        new EarlierMessage("user", "write a python function to parse this log"),
                        new EarlierMessage(
                                "assistant",
                                "Sure! Here you go:\n[PASTE: 4 lines, 54 chars]\n"
                                        + "Let me know if you want error handling."),
                        new EarlierMessage("user", "it crashes on empty lines"),
                        new EarlierMessage(
                                "assistant",
                                "Apologies. " + "Try this instead. ".repeat(13) + "Try this ... "
                                        + "Try this instead. ".repeat(14).strip())),
                state.earlierMessages());
    }

    @Test
    void build_capsEachSlotAndNeverSplitsAWord() {
        String longWords = "word ".repeat(1_000);
        TurnState state = build(threeTurnsOf(longWords, longWords, longWords, longWords, longWords))
                .orElseThrow();
        assertCapped(state.currentUserMessage(), 1_600);
        assertCapped(state.earlierMessages().get(0).content(), 600);
        assertCapped(state.earlierMessages().get(1).content(), 128);
        assertCapped(state.earlierMessages().get(2).content(), 600);
        assertCapped(state.earlierMessages().get(3).content(), 512);
    }

    private static void assertCapped(String content, int cap) {
        assertTrue(content.length() <= cap + HeadTailClip.ELLIPSIS.length(), cap + ": " + content.length());
        assertTrue(content.contains(HeadTailClip.ELLIPSIS), "an over-cap message is cut in the middle");
        for (String token : content.split(" ")) {
            assertTrue(token.equals("word") || token.equals("..."), "no word is split: " + token);
        }
    }

    @Test
    void build_marksPastesBeforeCapping() {
        String paste = "here:\n```\n" + "x = 1;\n".repeat(400) + "```\nstill failing";
        TurnState state = build(threeTurnsOf("q1", "a1", "q2", "a2", paste)).orElseThrow();
        assertEquals(
                "here:\n[PASTE: 402 lines, 2807 chars]\nstill failing",
                state.currentUserMessage(),
                "a paste collapses to its marker, so the user's own words survive the cap");
    }

    @Test
    void build_overBudgetDropsTheOldestPairOnly() {
        Caps tight = new Caps(100, 100, 100, 100, 250);
        String hundred = "x".repeat(100);
        TurnState state = build(tight, threeTurnsOf(hundred, hundred, "newest q", "newest a", hundred))
                .orElseThrow();
        assertEquals(hundred, state.currentUserMessage(), "the current message is never dropped");
        assertEquals(
                List.of(new EarlierMessage("user", "newest q"), new EarlierMessage("assistant", "newest a")),
                state.earlierMessages());
    }

    @Test
    void build_withinBudgetKeepsAllFour() {
        Caps roomy = new Caps(100, 100, 100, 100, 500);
        String hundred = "x".repeat(100);
        TurnState state = build(roomy, threeTurnsOf(hundred, hundred, "newest q", "newest a", hundred))
                .orElseThrow();
        assertEquals(4, state.earlierMessages().size());
    }

    // ---- output shape

    @Test
    void turnState_serializesCurrentMessageFirstThenEarlierMessages() throws Exception {
        TurnState state = new TurnState("now", List.of(new EarlierMessage("user", "before")));
        assertEquals(
                "{\"current_user_message\":\"now\",\"earlier_messages\":[{\"role\":\"user\",\"content\":\"before\"}]}",
                new ObjectMapper().writeValueAsString(state));
    }

    private static SubstrateObservation[] threeTurnsOf(
            String user1, String assistant1, String user2, String assistant2, String current) {
        return new SubstrateObservation[] {
            turn("t1", say("user", user1), say("assistant", assistant1)),
            turn("t2", say("user", user2), say("assistant", assistant2)),
            turn("t3", say("user", current), null)
        };
    }
}
