// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SessionTimelineBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");

    private static final String SYS = "{\"role\":\"system\",\"content\":\"You are support.\"}";
    private static final String Q1 = user("where is my order 42?");
    private static final String C1 = "{\"id\":\"c1\",\"type\":\"function\","
            + "\"function\":{\"name\":\"lookup_order\",\"arguments\":\"{\\\"id\\\":42}\"}}";
    private static final String TC1 = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + C1 + "]}";
    private static final String R1 = assistant("Order 42 ships tomorrow.");
    private static final String EXPEDITE = user("can you expedite it?");
    private static final String R1_TEXT = "Order 42 ships tomorrow.";
    private static final String SYSTEM_BILLING = "{\"role\":\"system\",\"content\":\"You are billing.\"}";

    @Test
    void buildsTheAcceptanceSessionInTimelineOrder() {
        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "sess-1", acceptanceSession());

        assertEquals(
                table("""
                    tr-a/a1#user:0  user_message     0     0  1  a1  agent  agent        a1  tr-a  cs-agent
                    tr-a/a2         llm              1   200  1  a1  agent  agent        a1  tr-a  cs-llm
                    tr-a/a3         tool             2   300  1  a1  agent  agent        a1  tr-a  cs-turn-a
                    tr-a/a5         llm              3   590  2  a4  agent  agent,agent  a4  tr-a  cs-billing-llm
                    tr-a/a4         agent            4   600  1  a1  agent  agent        a1  tr-a  cs-billing
                    tr-a/a6         llm              5   900  1  a1  agent  agent        a1  tr-a  cs-llm
                    tr-a/a6#reply:0 assistant_reply  6   900  2  a6  llm    agent,llm    a1  tr-a  cs-llm
                    tr-a/a1         agent            7   900  0  -   -      -            -   tr-a  cs-agent
                    tr-b/b1#user:0  user_message     8  1000  1  b1  agent  agent        b1  tr-b  cs-agent
                    tr-b/b4         step             9  1005  1  b1  agent  agent        b1  tr-b  cs-turn-b
                    tr-b/b2         llm             10  1400  1  b1  agent  agent        b1  tr-b  cs-llm
                    tr-b/b3         tool            11  1450  1  b1  agent  agent        b1  tr-b  cs-turn-b
                    tr-b/b1         agent           12  1500  0  -   -      -            -   tr-b  cs-agent
                    tr-b/b1#reply:0 assistant_reply 13  1500  1  b1  agent  agent        b1  tr-b  cs-agent
                    """),
                rows(timeline),
                "wrong order on a tie, an orchestrator message as a user message, a sub-agent return as a reply");

        List<JsonNode> data = data(timeline);
        assertEquals(json("{\"text\":\"where is my order 42?\",\"from_person\":true}"), data.get(0));
        assertEquals(json("{\"text\":\"can you expedite it?\",\"from_person\":true}"), data.get(8));
        assertEquals(
                json("{\"model\":\"gpt-x\",\"system_prompt\":\"You are support.\",\"new_messages\":[" + Q1 + "],"
                        + "\"output\":{\"text\":null,\"tool_calls\":[" + C1 + "],\"reasoning\":null},"
                        + "\"settings\":{},\"usage\":{\"input_tokens\":50,\"output_tokens\":10,"
                        + "\"cache_read_tokens\":null,\"cache_write_tokens\":null,\"reasoning_tokens\":null,"
                        + "\"total_tokens\":null}}"),
                data.get(1),
                "history counted as new messages, or the system prompt kept among them");
        assertEquals(json("\"You are billing.\""), data.get(3).get("system_prompt"));
        assertEquals(json(list(user("check refund status for 42"))), data.get(3).get("new_messages"));
        assertEquals(
                json(list(toolResult("c2", "no refund pending"))),
                data.get(5).get("new_messages"),
                "a tool-call-only assistant message is the boundary of what is new");
        assertEquals(
                json("\"Order 42 ships tomorrow.\""), data.get(5).get("output").get("text"));
        assertEquals(json(list(EXPEDITE)), data.get(10).get("new_messages"));
        assertEquals(
                json("{\"name\":\"lookup_order\",\"mcp\":false,\"status\":\"ok\",\"error_type\":null,"
                        + "\"input\":{\"id\":42},\"output\":{\"eta\":\"tomorrow\"}}"),
                data.get(2),
                "the tool name and arguments come from the tool call, not the wrapping span");
        assertEquals(
                json("{\"name\":\"expedite\",\"mcp\":true,\"status\":\"error\",\"error_type\":\"timeout\","
                        + "\"input\":{\"id\":42},\"output\":null}"),
                data.get(11),
                "an mcp span is a tool event that says it is mcp");
        assertEquals(json("{\"name\":\"stt\",\"input\":null,\"output\":null}"), data.get(9));
        assertEquals(json("{\"text\":\"Order 42 ships tomorrow.\"}"), data.get(6));
        assertEquals(json("{\"text\":\"Yes, I expedited order 42.\"}"), data.get(13));
        assertEquals(
                json("{\"name\":\"support\",\"status\":null,\"input\":[" + Q1 + "],\"output\":[" + R1 + "],"
                        + "\"attributes\":null}"),
                data.get(7));
    }

    @Test
    void aModelCallInsideAToolOfTheAgentIsNotThePersonsDialogue() {
        String question = user("what does my contract say about refunds?");
        String answer = "Refunds within 30 days.";
        String clause = user("Summarize the refund clause of this contract: ...");
        String args = "{\"kwargs\":{\"question\":\"what does my contract say about refunds?\"}}";
        String said = list(assistant(answer));
        List<TimelineSpan> spans = List.of(
                span("T", "ag", "agent", null, 0, 100, args, said),
                span("T", "l1", "llm", "ag", 1, 20, list(SYS, question), list(TC1)),
                span("T", "tool", "tool", "ag", 21, 60, null, null),
                span("T", "l2", "llm", "tool", 22, 59, list(clause), said),
                span("T", "l3", "llm", "ag", 61, 99, list(SYS, question, TC1, toolResult("c1", answer)), said));

        assertEquals(
                """
                T/l1#user:0 what does my contract say about refunds?
                T/l3#reply:0 Refunds within 30 days.
                """,
                messages(SessionTimelineBuilder.build("p1", "s", spans)),
                "a model call a tool makes reads as the person's dialogue: its prompt becomes the person's message"
                        + " and its text takes the place of the reply the agent loop wrote");
    }

    @Test
    void anAgentCalledAsAToolIsNotTheAgentThePersonTalksTo() {
        String find = user("find a free table for 2 at 8pm");
        String book = user("book a table");
        String free = "Table 7 is free.";
        String booked = "Booked table 7 for you.";
        List<TimelineSpan> spans = List.of(
                span("T", "w", "workflow", null, 0, 100, null, null),
                span("T", "l1", "llm", "w", 1, 10, list(book), list(TC1)),
                span("T", "tool", "tool", "w", 11, 80, null, null),
                span("T", "sub", "agent", "tool", 12, 79, list(find), free),
                span("T", "l2", "llm", "sub", 13, 78, list(find), free),
                span("T", "l3", "llm", "w", 81, 99, list(book, TC1, toolResult("c1", free)), booked));

        assertEquals(
                """
                T/l1#user:0 book a table
                T/l3#reply:0 Booked table 7 for you.
                """,
                messages(SessionTimelineBuilder.build("p1", "s", spans)),
                "an agent a tool runs reads as the agent the person talks to: the model's request becomes the"
                        + " person's message and the tool's answer a reply");
    }

    @Test
    void aPromptCodeWritesToAModelCallOfTheAgentIsNotAUserMessage() {
        List<TimelineSpan> spans = List.of(
                span("tr-m", "m1", "agent", null, 0, 500, list(Q1, R1, EXPEDITE), "Yes, expedited."),
                span("tr-m", "m2", "llm", "m1", 10, 100, list(Q1, R1, user("summarise the chat")), R1_TEXT),
                span("tr-m", "m3", "llm", "m1", 110, 490, list(Q1, R1, EXPEDITE), "Yes, expedited."));

        assertEquals(
                """
                tr-m/m1#user:0 can you expedite it?
                tr-m/m3#reply:0 Yes, expedited.
                """,
                messages(SessionTimelineBuilder.build("p1", "s", spans)),
                "a memory summary's instruction under the user role reads as the person's message, though the agent"
                        + " input says what the person sent");
    }

    @Test
    void aMessageThePersonAddsWhileTheAgentRunsIsAUserMessage() {
        String book = user("book a table");
        String find = calling(null, "c1", "find");
        String found = toolResult("c1", "free at 8");
        String four = user("actually make it for 4 people");
        String reserve = calling(null, "c2", "reserve");
        String reserved = toolResult("c2", "ok");
        String booked = "Booked for 4.";
        List<TimelineSpan> spans = List.of(
                span("t1", "a", "agent", null, 0, 500, list(book), booked),
                span("t1", "l1", "llm", "a", 10, 100, list(book), list(find)),
                span("t1", "x", "tool", "a", 110, 200, null, null),
                span("t1", "l2", "llm", "a", 210, 300, list(book, find, found, four), list(reserve)),
                span("t1", "y", "tool", "a", 310, 390, null, null),
                span("t1", "l3", "llm", "a", 400, 490, list(book, find, found, four, reserve, reserved), booked));

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                """
                t1/a#user:0 book a table
                t1/l2#user:0 actually make it for 4 people
                t1/l3#reply:0 Booked for 4.
                """,
                messages(timeline),
                "the agent input is taken as the only source of the turn's user messages, so the person's message"
                        + " that only a later call holds is dropped");
        assertEquals("t1/a#user:0 t1/l1 t1/x t1/l2#user:0 t1/l2 t1/y t1/l3 t1/l3#reply:0 t1/a", ids(timeline));
    }

    /** Catches an input guardrail on the root of a turn with no agent span coming before the message it checks. */
    @Test
    void aGuardrailBeforeTheFirstModelCallComesAfterTheMessage() {
        List<TimelineSpan> spans = List.of(
                span("tr-2", "w", "workflow", null, 0, 300, null, null),
                span("tr-2", "g", "guardrail", "w", 5, 40, null, null),
                span("tr-2", "l", "llm", "w", 50, 250, list(user("hi")), "Hello."));

        assertEquals(
                List.of("tr-2/l#user:0 0", "tr-2/g 40", "tr-2/l 250", "tr-2/l#reply:0 250", "tr-2/w 300"),
                idsAndMillis(SessionTimelineBuilder.build("p1", "s", spans)),
                "a guardrail that checks the message comes after it");
    }

    /**
     * Catches a tool event that loses what the tool returned or received: ingest stores only a JSON object or array in
     * the tool_call row, so a tool that returns text, or takes text, has it only in the span's payload.
     */
    @Test
    void aToolThatTakesAndReturnsTextKeepsItsInputAndOutput() {
        List<TimelineSpan> spans = List.of(
                rich("tr-t", "t1", "tool", null, 0, 20, "friday", "free at 8")
                        .tool("find_slot", null, null)
                        .build(),
                rich("tr-t", "t2", "tool", null, 30, 40, null, "ok")
                        .tool("book", "{\"day\":\"fri\"}", null)
                        .build());

        assertEquals(
                List.of(
                        json("{\"name\":\"find_slot\",\"mcp\":false,\"status\":null,\"error_type\":null,"
                                + "\"input\":\"friday\",\"output\":\"free at 8\"}"),
                        json("{\"name\":\"book\",\"mcp\":false,\"status\":null,\"error_type\":null,"
                                + "\"input\":{\"day\":\"fri\"},\"output\":\"ok\"}")),
                data(SessionTimelineBuilder.build("p1", "s", spans)),
                "a tool's text input or result is in its event");
    }

    @Test
    void anOpenSpanGivesItsUserMessageButNoEventOrReply() {
        List<TimelineSpan> spans =
                List.of(span("tr-o", "o1", "llm", null, 0, null, list(user("hello")), list(assistant("hi"))));

        assertEquals(
                table("tr-o/o1#user:0 user_message 0 0 1 o1 llm llm - tr-o -"),
                rows(SessionTimelineBuilder.build("p1", "s", spans)),
                "an incomplete span is judged, or the user message is lost while the span runs");
    }

    @Test
    void aDetachedSubAgentTraceHasNoMessagesOfThePerson() {
        List<TimelineSpan> spans =
                List.of(rich("tr-d", "d1", "agent", null, 0, 100, list(user("summarise the ticket")), "summary")
                        .parentTrace("tr-parent")
                        .build());

        assertEquals(
                table("tr-d/d1 agent 0 100 0 - - - - tr-d -"),
                rows(SessionTimelineBuilder.build("p1", "s", spans)),
                "a message a model wrote to a detached sub-agent is counted as the person's");
    }

    @Test
    void aToolResultSentUnderTheUserRoleIsNotAUserMessage() {
        String toolResult =
                "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"ok\"}]}";
        String toolUse =
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"x\",\"input\":{}}]}";
        List<TimelineSpan> spans =
                List.of(span("tr-k", "k1", "llm", null, 0, 100, list(user("q"), toolUse, toolResult), null));

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("llm"),
                timeline.events().stream().map(Event::kind).toList(),
                "an Anthropic tool result is counted as the person's words");
        assertEquals(json(list(toolResult)), data(timeline).get(0).get("new_messages"));
    }

    @Test
    void aLongAgentLoopReadsEachInputOnce() {
        int calls = 400;
        List<TimelineSpan> spans = new ArrayList<>();
        spans.add(span("tr-c", "c", "agent", null, 0, calls * 10 + 5, null, "Done."));
        StringBuilder history = new StringBuilder(user("fix the build"));
        for (int i = 0; i < calls; i++) {
            String step = calling("Step " + i + ": checking.", "c" + i, "run");
            spans.add(span("tr-c", "l" + i, "llm", "c", i * 10L, i * 10 + 9, list(history.toString()), list(step)));
            history.append(',').append(step).append(',').append(toolResult("c" + i, "x".repeat(200)));
        }

        SessionTimeline timeline = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> SessionTimelineBuilder.build("p1", "s", spans),
                "each text of each call parses every input of the trace again, so a long loop takes minutes");

        assertEquals(
                calls,
                replyIds(timeline).split(" ").length,
                "every step the next call received, and the agent's answer");
    }

    @Test
    void aTraceWhoseTopAgentHasNotArrivedGivesNoMessageUntilItDoes() {
        String where = user("where is order 42");
        TimelineSpan a1 = span("T", "a1", "agent", null, 0, 1000, list(where), null);
        TimelineSpan l1 = span("T", "l1", "llm", "a1", 100, 190, list(SYS, where), list(TC1));
        TimelineSpan s1 = span("T", "s1", "agent", "a1", 200, 600, null, null);
        String docs = list(user("find shipping docs for order 42"));
        String dhl = "Order 42 ships via DHL tomorrow.";
        TimelineSpan l2 = span("T", "l2", "llm", "s1", 250, 550, docs, "internal: carrier is DHL");
        TimelineSpan l3 = span("T", "l3", "llm", "a1", 700, 950, list(SYS, where, TC1, toolResult("c1", "DHL")), dhl);

        SessionTimeline inFlight = SessionTimelineBuilder.assemble(
                "p1", "s", SessionTimelineBuilder.derive(List.of(l1, s1, l2, l3), false), false);
        SessionTimeline arrived = SessionTimelineBuilder.assemble(
                "p1", "s", SessionTimelineBuilder.derive(List.of(l1, s1, l2, l3, a1), false), false);

        assertEquals(
                "T/l1 T/l2 T/s1 T/l3",
                ids(inFlight),
                "a sub-agent whose top agent is not loaded yet reads as the agent the person talks to");
        assertEquals("""
            T/a1#user:0 where is order 42
            T/l3#reply:0 Order 42 ships via DHL tomorrow.
            """, messages(arrived));
    }

    @Test
    void theAgentLoopsRepeatedTextIsTwoReplies() {
        String u = user("expedite 42");
        String first = calling("Let me check.", "c1", "expedite");
        String second = calling("Let me check.", "c2", "status");
        String t1 = toolResult("c1", "queued");
        List<TimelineSpan> spans = List.of(
                span("t", "g1", "agent", null, 0, 500, null, "Done."),
                span("t", "g2", "llm", "g1", 10, 100, list(u), list(first)),
                span("t", "g4", "llm", "g1", 200, 300, list(u, first, t1), list(second)),
                span("t", "g6", "llm", "g1", 400, 490, list(u, first, t1, second, toolResult("c2", "done")), "Done."));

        assertEquals(
                "t/g2#reply:0 t/g4#reply:0 t/g6#reply:0",
                replyIds(SessionTimelineBuilder.build("p1", "s", spans)),
                "a second message with the same text is taken for the first one");
    }

    @Test
    void aPersonWhoSaysTheSameWordsTwiceInOneTurnGivesTwoMessages() {
        String yes = user("yes");
        String ask = assistant("Delete all 3 files, ok?");
        List<TimelineSpan> spans = List.of(
                span("T", "l1", "llm", null, 0, 100, list(SYS, yes), list(ask)),
                span("T", "l2", "llm", null, 200, 300, list(SYS, yes, ask, yes), "Deleted."));

        assertEquals(
                """
                T/l1#user:0 yes
                T/l1#reply:0 Delete all 3 files, ok?
                T/l2#user:0 yes
                T/l2#reply:0 Deleted.
                """,
                messages(SessionTimelineBuilder.build("p1", "s", spans)),
                "an answer the person gave after a reply of this turn is dropped because an earlier message had its"
                        + " text");
    }

    @Test
    void aPersonWhoRepeatsTheOpeningWordsWhileTheAgentRunsGivesTwoMessages() {
        String yes = user("yes");
        String ask = assistant("Delete all 3 files, ok?");
        List<TimelineSpan> spans = List.of(
                span("T3", "a", "agent", null, 0, 500, list(yes), "Deleted."),
                span("T3", "l1", "llm", "a", 10, 100, list(yes), list(ask)),
                span("T3", "l2", "llm", "a", 200, 490, list(yes, ask, yes), "Deleted."));

        assertEquals(
                """
                T3/a#user:0 yes
                T3/l1#reply:0 Delete all 3 files, ok?
                T3/l2#user:0 yes
                T3/l2#reply:0 Deleted.
                """,
                messages(SessionTimelineBuilder.build("p1", "s", spans)),
                "the person's second yes is taken for the agent's opening message, so the answer to the question is"
                        + " dropped");
    }

    @Test
    void anAgentThatCarriesOnlyTheNewMessageDoesNotCountItTwice() {
        String yes = user("yes");
        List<TimelineSpan> spans = List.of(
                span("T2", "b1", "agent", null, 0, 500, list(yes), "Done."),
                span("T2", "b2", "llm", "b1", 10, 490, list(SYS, yes, assistant("Sure?"), yes), "Done."));

        assertEquals(
                """
                T2/b1#user:0 yes
                T2/b2#reply:0 Done.
                """,
                messages(SessionTimelineBuilder.build("p1", "s", spans)),
                "the history of an earlier turn makes the model call's copy of the message a second message");
    }

    @Test
    void aTurnOfTheSameTraceThatStartsWhenThePreviousEndsComesAfterIt() {
        String hi = user("hi");
        String hello = assistant("Hello, how can I help?");
        String cancel = user("cancel my order");
        List<TimelineSpan> spans = List.of(
                span("t3", "root", "workflow", null, 0, 1000, null, null),
                span("t3", "ag1", "agent", "root", 10, 400, list(hi), "Hello, how can I help?"),
                span("t3", "ag2", "agent", "root", 400, 900, list(hi, hello, cancel), "Done, it is cancelled."));

        assertEquals(
                "t3/ag1#user:0 t3/ag1 t3/ag1#reply:0 t3/ag2#user:0 t3/ag2 t3/ag2#reply:0 t3/root",
                ids(SessionTimelineBuilder.build("p1", "s", spans)),
                "the next turn's message, one level deeper, sorts before the turn of the same trace that ended in"
                        + " its instant, so it comes before the reply it answers");
    }

    @Test
    void aMessageComesBeforeTheSpansAboveItThatEndInItsInstant() {
        String a = user("a");
        String b = assistant("b");
        List<TimelineSpan> spans = List.of(
                span("z", "w", "workflow", null, 0, 400, null, null),
                span("z", "l0", "llm", "w", 100, 400, list(a), list(b)),
                span("z", "l1", "llm", "w", 400, 400, list(a, b, user("c")), "d"));

        assertEquals(
                "z/l0#user:0 z/l0 z/l0#reply:0 z/l1#user:0 z/l1 z/l1#reply:0 z/w",
                ids(SessionTimelineBuilder.build("p1", "s", spans)),
                "a span that ends in the instant its zero-length child starts comes before the child, or the"
                        + " message comes before a reply it answers");
    }

    /** Section 5.1 of the CC-01 plan: two turns, a nested billing agent, a resent history, a reply on an agent. */
    static List<TimelineSpan> acceptanceSession() {
        String checkRefund = user("check refund status for 42");
        String noRefund = list(assistant("no refund pending"));
        String expedite = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c3\","
                + "\"type\":\"function\",\"function\":{\"name\":\"expedite\",\"arguments\":\"{\\\"id\\\":42}\"}}]}";
        String turnA = list(
                SYS,
                Q1,
                TC1,
                toolResult("c1", "{\\\"eta\\\":\\\"tomorrow\\\"}"),
                calling(null, "c2", "billing"),
                toolResult("c2", "no refund pending"));
        return List.of(
                rich("tr-a", "a1", "agent", null, 0, 900, list(Q1), list(R1))
                        .name("support")
                        .callSite("cs-agent")
                        .build(),
                rich("tr-a", "a2", "llm", "a1", 10, 200, list(SYS, Q1), list(TC1))
                        .callSite("cs-llm")
                        .model("gpt-x")
                        .usage(50L, 10L)
                        .build(),
                rich("tr-a", "a3", "tool", "a1", 210, 300, null, null)
                        .name("execute_tool")
                        .callSite("cs-turn-a")
                        .status("ok")
                        .tool("lookup_order", "{\"id\":42}", "{\"eta\":\"tomorrow\"}")
                        .build(),
                rich("tr-a", "a4", "agent", "a1", 310, 600, list(checkRefund), noRefund)
                        .name("billing")
                        .callSite("cs-billing")
                        .build(),
                rich("tr-a", "a5", "llm", "a4", 320, 590, list(SYSTEM_BILLING, checkRefund), noRefund)
                        .callSite("cs-billing-llm")
                        .build(),
                rich("tr-a", "a6", "llm", "a1", 610, 900, turnA, list(R1))
                        .callSite("cs-llm")
                        .build(),
                rich("tr-b", "b1", "agent", null, 1000, 1500, list(Q1, R1, EXPEDITE), "Yes, I expedited order 42.")
                        .name("support")
                        .callSite("cs-agent")
                        .build(),
                rich("tr-b", "b4", "step", "b1", 1001, 1005, null, null)
                        .name("stt")
                        .callSite("cs-turn-b")
                        .build(),
                rich("tr-b", "b2", "llm", "b1", 1010, 1400, list(SYS, Q1, R1, EXPEDITE), list(expedite))
                        .callSite("cs-llm")
                        .build(),
                rich("tr-b", "b3", "mcp", "b1", 1410, 1450, null, null)
                        .name("execute_tool")
                        .callSite("cs-turn-b")
                        .status("error")
                        .errorType("timeout")
                        .tool("expedite", "{\"id\":42}", null)
                        .build());
    }

    private static String user(String text) {
        return "{\"role\":\"user\",\"content\":\"" + text + "\"}";
    }

    private static String assistant(String text) {
        return "{\"role\":\"assistant\",\"content\":\"" + text + "\"}";
    }

    private static String toolResult(String callId, String content) {
        return "{\"role\":\"tool\",\"tool_call_id\":\"" + callId + "\",\"content\":\"" + content + "\"}";
    }

    /** An assistant message that calls {@code tool} with no arguments; a null text is a tool-call-only message. */
    private static String calling(@Nullable String text, String callId, String tool) {
        return "{\"role\":\"assistant\",\"content\":" + (text == null ? "null" : "\"" + text + "\"")
                + ",\"tool_calls\":[{\"id\":\"" + callId + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool
                + "\",\"arguments\":\"{}\"}}]}";
    }

    private static String list(String... messages) {
        return "[" + String.join(",", messages) + "]";
    }

    /**
     * Each event as one row: id, kind, position, millis after T0, depth, parent id, parent kind, ancestor kinds,
     * agent id, turn, call site; a dash for a null or empty value.
     */
    private static String rows(SessionTimeline timeline) {
        return timeline.events().stream()
                .map(e -> String.join(
                        " ",
                        e.id(),
                        e.kind(),
                        String.valueOf(e.position()),
                        millis(e.at()),
                        String.valueOf(e.depth()),
                        dash(e.parentId()),
                        dash(e.parentKind()),
                        e.ancestorKinds().isEmpty() ? "-" : String.join(",", e.ancestorKinds()),
                        dash(e.agentId()),
                        e.turn(),
                        dash(e.callSiteId())))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /** A hand-written {@link #rows} table, its columns aligned with any run of spaces. */
    private static String table(String rows) {
        return rows.strip().replaceAll(" +", " ") + "\n";
    }

    private static String dash(@Nullable String value) {
        return value == null ? "-" : value;
    }

    private static String millis(Instant at) {
        Duration since = Duration.between(T0, at);
        return since.toNanosPart() % 1_000_000 == 0 ? String.valueOf(since.toMillis()) : at.toString();
    }

    /** Each message event as {@code "<id> <text>"}, one per line, in timeline order. */
    private static String messages(SessionTimeline timeline) {
        return timeline.events().stream()
                .filter(e -> e.kind().equals(Event.USER_MESSAGE) || e.kind().equals(Event.ASSISTANT_REPLY))
                .map(e -> e.id() + " " + e.data().get("text").asText() + "\n")
                .collect(Collectors.joining());
    }

    /** Each event as {@code "<id> <ms after T0>"}, in timeline order. */
    private static List<String> idsAndMillis(SessionTimeline timeline) {
        return timeline.events().stream()
                .map(e -> e.id() + " " + Duration.between(T0, e.at()).toMillis())
                .toList();
    }

    private static String ids(SessionTimeline timeline) {
        return timeline.events().stream().map(Event::id).collect(Collectors.joining(" "));
    }

    private static String replyIds(SessionTimeline timeline) {
        return timeline.events().stream()
                .filter(e -> e.kind().equals(Event.ASSISTANT_REPLY))
                .map(Event::id)
                .collect(Collectors.joining(" "));
    }

    /** The data nodes re-read from text, so a long the builder wrote compares equal to the int a test parses. */
    private static List<JsonNode> data(SessionTimeline timeline) {
        return timeline.events().stream().map(e -> json(e.data().toString())).toList();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    /** A span named "chat", ending {@code endMs} after T0, or still open when that is null. */
    private static TimelineSpan span(
            String traceId,
            String spanId,
            String kind,
            @Nullable String parentSpanId,
            long startMs,
            @Nullable Integer endMs,
            @Nullable String input,
            @Nullable String output) {
        return rich(traceId, spanId, kind, parentSpanId, startMs, endMs, input, output)
                .build();
    }

    private static SpanBuilder rich(
            String traceId,
            String spanId,
            String kind,
            @Nullable String parentSpanId,
            long startMs,
            @Nullable Integer endMs,
            @Nullable String input,
            @Nullable String output) {
        SpanBuilder b = new SpanBuilder(traceId, spanId, kind);
        b.parentSpanId = parentSpanId;
        b.startedAt = T0.plusMillis(startMs);
        b.endedAt = endMs == null ? null : T0.plusMillis(endMs);
        b.input = input;
        b.output = output;
        return b;
    }

    private static final class SpanBuilder {
        private final String traceId;
        private final String spanId;
        private final String kind;
        private @Nullable String parentTraceId;
        private @Nullable String parentSpanId;
        private @Nullable String name = "chat";
        private @Nullable String callSiteId;
        private @Nullable String status;
        private @Nullable String errorType;
        private Instant startedAt = T0;
        private @Nullable Instant endedAt = T0;
        private @Nullable String model;
        private TimelineSpan.Usage usage = TimelineSpan.Usage.NONE;
        private @Nullable String input;
        private @Nullable String output;
        private TimelineSpan.@Nullable Tool tool;

        SpanBuilder(String traceId, String spanId, String kind) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.kind = kind;
        }

        SpanBuilder parentTrace(String v) {
            this.parentTraceId = v;
            return this;
        }

        SpanBuilder name(String v) {
            this.name = v;
            return this;
        }

        SpanBuilder callSite(String v) {
            this.callSiteId = v;
            return this;
        }

        SpanBuilder status(String v) {
            this.status = v;
            return this;
        }

        SpanBuilder errorType(String v) {
            this.errorType = v;
            return this;
        }

        SpanBuilder model(String v) {
            this.model = v;
            return this;
        }

        SpanBuilder usage(Long in, Long out) {
            this.usage = new TimelineSpan.Usage(in, out, null, null, null, null);
            return this;
        }

        SpanBuilder tool(String toolName, @Nullable String arguments, @Nullable String result) {
            this.tool = new TimelineSpan.Tool(toolName, arguments, result);
            return this;
        }

        TimelineSpan build() {
            return new TimelineSpan(
                    traceId,
                    parentTraceId,
                    spanId,
                    parentSpanId,
                    kind,
                    name,
                    callSiteId,
                    status,
                    errorType,
                    startedAt,
                    endedAt,
                    model,
                    usage,
                    input,
                    output,
                    null,
                    tool);
        }
    }
}
