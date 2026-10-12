// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SessionTimelineBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");

    private static final String SYS = "{\"role\":\"system\",\"content\":\"You are support.\"}";
    private static final String Q1 = "{\"role\":\"user\",\"content\":\"where is my order 42?\"}";
    private static final String C1 = "{\"id\":\"c1\",\"type\":\"function\","
            + "\"function\":{\"name\":\"lookup_order\",\"arguments\":\"{\\\"id\\\":42}\"}}";
    private static final String TC1 = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + C1 + "]}";
    private static final String R1 = "{\"role\":\"assistant\",\"content\":\"Order 42 ships tomorrow.\"}";
    private static final String EXPEDITE = "{\"role\":\"user\",\"content\":\"can you expedite it?\"}";

    private record Row(
            String id,
            String kind,
            int position,
            Instant at,
            int depth,
            @Nullable String parentId,
            @Nullable String parentKind,
            List<String> ancestorKinds,
            @Nullable String agentId,
            String turn,
            @Nullable String callSiteId) {}

    @Test
    void buildsTheAcceptanceSessionInTimelineOrder() {
        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "sess-1", acceptanceSession());

        assertEquals(
                List.of(
                        new Row(
                                "tr-a/a1#user:0",
                                "user_message",
                                0,
                                at(0),
                                1,
                                "a1",
                                "agent",
                                List.of("agent"),
                                "a1",
                                "tr-a",
                                "cs-agent"),
                        new Row(
                                "tr-a/a2",
                                "llm",
                                1,
                                at(200),
                                1,
                                "a1",
                                "agent",
                                List.of("agent"),
                                "a1",
                                "tr-a",
                                "cs-llm"),
                        new Row(
                                "tr-a/a3",
                                "tool",
                                2,
                                at(300),
                                1,
                                "a1",
                                "agent",
                                List.of("agent"),
                                "a1",
                                "tr-a",
                                "cs-turn-a"),
                        new Row(
                                "tr-a/a5",
                                "llm",
                                3,
                                at(590),
                                2,
                                "a4",
                                "agent",
                                List.of("agent", "agent"),
                                "a4",
                                "tr-a",
                                "cs-billing-llm"),
                        new Row(
                                "tr-a/a4",
                                "agent",
                                4,
                                at(600),
                                1,
                                "a1",
                                "agent",
                                List.of("agent"),
                                "a1",
                                "tr-a",
                                "cs-billing"),
                        new Row(
                                "tr-a/a6",
                                "llm",
                                5,
                                at(900),
                                1,
                                "a1",
                                "agent",
                                List.of("agent"),
                                "a1",
                                "tr-a",
                                "cs-llm"),
                        new Row(
                                "tr-a/a6#reply:0",
                                "assistant_reply",
                                6,
                                at(900),
                                2,
                                "a6",
                                "llm",
                                List.of("agent", "llm"),
                                "a1",
                                "tr-a",
                                "cs-llm"),
                        new Row("tr-a/a1", "agent", 7, at(900), 0, null, null, List.of(), null, "tr-a", "cs-agent"),
                        new Row(
                                "tr-b/b1#user:0",
                                "user_message",
                                8,
                                at(1000),
                                1,
                                "b1",
                                "agent",
                                List.of("agent"),
                                "b1",
                                "tr-b",
                                "cs-agent"),
                        new Row(
                                "tr-b/b4",
                                "step",
                                9,
                                at(1005),
                                1,
                                "b1",
                                "agent",
                                List.of("agent"),
                                "b1",
                                "tr-b",
                                "cs-turn-b"),
                        new Row(
                                "tr-b/b2",
                                "llm",
                                10,
                                at(1400),
                                1,
                                "b1",
                                "agent",
                                List.of("agent"),
                                "b1",
                                "tr-b",
                                "cs-llm"),
                        new Row(
                                "tr-b/b3",
                                "tool",
                                11,
                                at(1450),
                                1,
                                "b1",
                                "agent",
                                List.of("agent"),
                                "b1",
                                "tr-b",
                                "cs-turn-b"),
                        new Row("tr-b/b1", "agent", 12, at(1500), 0, null, null, List.of(), null, "tr-b", "cs-agent"),
                        new Row(
                                "tr-b/b1#reply:0",
                                "assistant_reply",
                                13,
                                at(1500),
                                1,
                                "b1",
                                "agent",
                                List.of("agent"),
                                "b1",
                                "tr-b",
                                "cs-agent")),
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
        assertEquals(
                json("[{\"role\":\"user\",\"content\":\"check refund status for 42\"}]"),
                data.get(3).get("new_messages"));
        assertEquals(
                json("[{\"role\":\"tool\",\"tool_call_id\":\"c2\",\"content\":\"no refund pending\"}]"),
                data.get(5).get("new_messages"),
                "a tool-call-only assistant message is the boundary of what is new");
        assertEquals(
                json("\"Order 42 ships tomorrow.\""), data.get(5).get("output").get("text"));
        assertEquals(json("[" + EXPEDITE + "]"), data.get(10).get("new_messages"));
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
    void aRouterInsideTheAgentIsNotAReply() {
        List<TimelineSpan> spans = List.of(
                span("tr-r", "r1", "agent")
                        .at(0, 300)
                        .out("Your refund is on its way.")
                        .build(),
                span("tr-r", "r2", "llm")
                        .parent("r1")
                        .at(10, 50)
                        .in("[{\"role\":\"user\",\"content\":\"where is my refund?\"}]")
                        .out("billing")
                        .build(),
                span("tr-r", "r3", "llm")
                        .parent("r1")
                        .at(60, 200)
                        .in("[{\"role\":\"user\",\"content\":\"where is my refund?\"}]")
                        .out("Your refund is on its way.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-r/r3#reply:0"),
                replyIds(timeline),
                "a router's text is never shown to the user, so only the text the agent returned is a reply");
    }

    @Test
    void theAgentLoopsOwnTextIsAReply() {
        String letMeCheck = "{\"role\":\"assistant\",\"content\":\"Let me check.\",\"tool_calls\":[{\"id\":\"c9\","
                + "\"type\":\"function\",\"function\":{\"name\":\"expedite\",\"arguments\":\"{}\"}}]}";
        List<TimelineSpan> spans = List.of(
                span("tr-g", "g1", "agent")
                        .at(0, 500)
                        .out("Done: order 42 is expedited.")
                        .build(),
                span("tr-g", "g2", "llm")
                        .parent("g1")
                        .at(10, 100)
                        .in("[{\"role\":\"user\",\"content\":\"expedite 42\"}]")
                        .out("[" + letMeCheck + "]")
                        .build(),
                span("tr-g", "g3", "tool").parent("g1").at(110, 200).build(),
                span("tr-g", "g4", "llm")
                        .parent("g1")
                        .at(210, 490)
                        .in("[{\"role\":\"user\",\"content\":\"expedite 42\"}," + letMeCheck
                                + ",{\"role\":\"tool\",\"tool_call_id\":\"c9\",\"content\":\"ok\"}]")
                        .out("Done: order 42 is expedited.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-g/g2#reply:0", "tr-g/g4#reply:0"),
                replyIds(timeline),
                "treating the agent output as the only reply loses the first reply of the turn");
        assertEquals(
                List.of("Let me check.", "Done: order 42 is expedited."),
                timeline.events().stream()
                        .filter(e -> e.kind().equals(Event.ASSISTANT_REPLY))
                        .map(e -> e.data().get("text").asText())
                        .toList());
    }

    @Test
    void theAgentLoopsOwnTextIsAReplyWhenTheNextCallStartsTheSameMillisecond() {
        String letMeCheck = "{\"role\":\"assistant\",\"content\":\"Let me check.\",\"tool_calls\":[{\"id\":\"c9\","
                + "\"type\":\"function\",\"function\":{\"name\":\"expedite\",\"arguments\":\"{}\"}}]}";
        List<TimelineSpan> spans = List.of(
                span("tr-g", "g1", "agent")
                        .at(0, 500)
                        .out("Done: order 42 is expedited.")
                        .build(),
                span("tr-g", "g2", "llm")
                        .parent("g1")
                        .at(10, 100)
                        .in("[{\"role\":\"user\",\"content\":\"expedite 42\"}]")
                        .out("[" + letMeCheck + "]")
                        .build(),
                span("tr-g", "g4", "llm")
                        .parent("g1")
                        .at(100, 490)
                        .in("[{\"role\":\"user\",\"content\":\"expedite 42\"}," + letMeCheck
                                + ",{\"role\":\"tool\",\"tool_call_id\":\"c9\",\"content\":\"ok\"}]")
                        .out("Done: order 42 is expedited.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-g/g2#reply:0", "tr-g/g4#reply:0"),
                replyIds(timeline),
                "a clock boundary drops a reply when the next model call starts in the millisecond the last one ended");
    }

    @Test
    void aSummaryThatRepeatsAnEarlierReplyIsNotAReply() {
        String history = "{\"role\":\"user\",\"content\":\"where is my order 42?\"}," + R1;
        List<TimelineSpan> spans = List.of(
                span("tr-m", "m1", "agent").at(0, 500).out("Yes, expedited.").build(),
                span("tr-m", "m2", "llm")
                        .parent("m1")
                        .at(10, 100)
                        .in("[" + history + ",{\"role\":\"user\",\"content\":\"summarise the chat\"}]")
                        .out("Order 42 ships tomorrow.")
                        .build(),
                span("tr-m", "m3", "llm")
                        .parent("m1")
                        .at(110, 490)
                        .in("[" + history + "," + EXPEDITE + "]")
                        .out("Yes, expedited.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-m/m3#reply:0"),
                replyIds(timeline),
                "a text a later call carries only as old history is counted as shown again");
    }

    @Test
    void anAppWithoutAgentSpansRepliesFromEachTextCall() {
        String toolCallOnly = "[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"x\",\"arguments\":\"{}\"}}]}]";
        List<TimelineSpan> spans = List.of(
                span("tr-l", "l1", "llm").at(0, 10).out("A").build(),
                span("tr-l", "l2", "llm").at(20, 30).out(toolCallOnly).build(),
                span("tr-l", "l3", "llm").at(40, 50).out("B").build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-l/l1#reply:0", "tr-l/l3#reply:0"),
                replyIds(timeline),
                "a tool-call-only output is not a reply, and an app with no agent span still has replies");
    }

    @Test
    void anOpenSpanGivesItsUserMessageButNoEventOrReply() {
        List<TimelineSpan> spans = List.of(span("tr-o", "o1", "llm")
                .at(0, null)
                .in("[{\"role\":\"user\",\"content\":\"hello\"}]")
                .out("[{\"role\":\"assistant\",\"content\":\"hi\"}]")
                .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of(new Row(
                        "tr-o/o1#user:0",
                        "user_message",
                        0,
                        at(0),
                        1,
                        "o1",
                        "llm",
                        List.of("llm"),
                        null,
                        "tr-o",
                        null)),
                rows(timeline),
                "an incomplete span is judged, or the user message is lost while the span runs");
    }

    @Test
    void aDetachedSubAgentTraceHasNoMessagesOfThePerson() {
        List<TimelineSpan> spans = List.of(span("tr-d", "d1", "agent")
                .parentTrace("tr-parent")
                .at(0, 100)
                .in("[{\"role\":\"user\",\"content\":\"summarise the ticket\"}]")
                .out("summary")
                .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of(new Row("tr-d/d1", "agent", 0, at(100), 0, null, null, List.of(), null, "tr-d", null)),
                rows(timeline),
                "a message a model wrote to a detached sub-agent is counted as the person's");
    }

    @Test
    void aToolResultSentUnderTheUserRoleIsNotAUserMessage() {
        String toolResult =
                "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"ok\"}]}";
        List<TimelineSpan> spans = List.of(span("tr-k", "k1", "llm")
                .at(0, 100)
                .in(
                        "[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\","
                                + "\"id\":\"t1\",\"name\":\"x\",\"input\":{}}]}," + toolResult + "]")
                .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("llm"),
                timeline.events().stream().map(Event::kind).toList(),
                "an Anthropic tool result is counted as the person's words");
        assertEquals(json("[" + toolResult + "]"), data(timeline).get(0).get("new_messages"));
    }

    @Test
    void anOrphanSpanIsARootOfItsOwn() {
        List<TimelineSpan> spans =
                List.of(span("tr-x", "x1", "llm").parent("missing").at(0, 100).build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of(new Row("tr-x/x1", "llm", 0, at(100), 0, "missing", null, List.of(), null, "tr-x", null)),
                rows(timeline),
                "a span whose parent never arrived crashes the builder or gets a wrong depth");
    }

    @Test
    void aKindTheBuilderDoesNotKnowPassesThrough() {
        List<TimelineSpan> spans =
                List.of(span("tr-n", "n1", "reranker").at(0, 100).build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        Event event = timeline.events().get(0);
        assertEquals("reranker", event.kind(), "a new canonical kind is dropped or renamed");
        List<String> keys = new ArrayList<>();
        event.data().fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("name", "status", "input", "output", "attributes"), Set.copyOf(keys));
    }

    @Test
    void tailKeepsTheNewestEventsThatFit() {
        SessionTimeline full = SessionTimelineBuilder.build("p1", "sess-1", acceptanceSession());
        long b12 = full.events().get(12).bytes();
        long b13 = full.events().get(13).bytes();

        SessionTimeline two = full.tail(b12 + b13);
        SessionTimeline one = full.tail(b12 + b13 - 1);

        assertEquals(
                List.of("tr-b/b1", "tr-b/b1#reply:0"),
                two.events().stream().map(Event::id).toList());
        assertEquals(
                List.of(0, 1),
                two.events().stream().map(Event::position).toList(),
                "a tail that keeps the old positions");
        assertEquals(true, two.truncated());
        assertEquals(
                List.of("tr-b/b1#reply:0"),
                one.events().stream().map(Event::id).toList(),
                "a tail that cuts from the wrong end");
        assertEquals(full, full.tail(full.bytes()), "a tail that fits the whole timeline is the timeline");
        assertEquals(false, full.truncated());
    }

    @Test
    void twoTracesThatReuseASpanIdGiveDistinctEventIds() {
        List<TimelineSpan> spans = List.of(
                span("tr-1", "s1", "llm")
                        .at(0, 100)
                        .in("[{\"role\":\"user\",\"content\":\"hi\"}]")
                        .out("hello")
                        .build(),
                span("tr-2", "s1", "llm")
                        .at(200, 300)
                        .in("[{\"role\":\"user\",\"content\":\"bye\"}]")
                        .out("goodbye")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-1/s1#user:0", "tr-1/s1", "tr-1/s1#reply:0", "tr-2/s1#user:0", "tr-2/s1", "tr-2/s1#reply:0"),
                timeline.events().stream().map(Event::id).toList(),
                "a span id is unique only inside its trace, so a bare span id gives two events one id");
    }

    @Test
    void aPlainStringInputIsOneUserMessage() {
        List<TimelineSpan> spans = List.of(
                span("tr-p", "p1", "llm").at(0, 100).in("the export is empty").build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-p/p1#user:0", "tr-p/p1"),
                timeline.events().stream().map(Event::id).toList(),
                "a producer that sends a plain string loses the person's message");
        assertEquals(
                json("{\"text\":\"the export is empty\",\"from_person\":true}"),
                data(timeline).get(0));
        assertEquals(
                json("[{\"role\":\"user\",\"content\":\"the export is empty\"}]"),
                data(timeline).get(1).get("new_messages"));
    }

    @Test
    void aLongAgentLoopReadsEachInputOnce() {
        int calls = 400;
        List<TimelineSpan> spans = new ArrayList<>();
        spans.add(span("tr-c", "c", "agent").at(0, calls * 10 + 5).out("Done.").build());
        StringBuilder history = new StringBuilder("{\"role\":\"user\",\"content\":\"fix the build\"}");
        for (int i = 0; i < calls; i++) {
            String step =
                    "{\"role\":\"assistant\",\"content\":\"Step " + i + ": checking.\",\"tool_calls\":[{\"id\":\"c" + i
                            + "\",\"type\":\"function\",\"function\":{\"name\":\"run\",\"arguments\":\"{}\"}}]}";
            spans.add(span("tr-c", "l" + i, "llm")
                    .parent("c")
                    .at(i * 10L, i * 10 + 9)
                    .in("[" + history + "]")
                    .out("[" + step + "]")
                    .build());
            history.append(',')
                    .append(step)
                    .append(",{\"role\":\"tool\",\"tool_call_id\":\"c")
                    .append(i)
                    .append("\",\"content\":\"")
                    .append("x".repeat(200))
                    .append("\"}");
        }

        SessionTimeline timeline = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> SessionTimelineBuilder.build("p1", "s", spans),
                "each text of each call parses every input of the trace again, so a long loop takes minutes");

        assertEquals(calls, replyIds(timeline).size(), "every step the next call received, and the agent's answer");
    }

    @Test
    void anAgentPayloadThatIsNotAMessageListGivesNoMessage() {
        List<TimelineSpan> spans = List.of(
                span("T", "a1", "agent")
                        .at(0, 500)
                        .in("{\"args\":[],\"kwargs\":{\"question\":\"where is order 42\"}}")
                        .out("{\"answer\":\"Order 42 ships tomorrow.\"}")
                        .build(),
                span("T", "l1", "llm")
                        .parent("a1")
                        .at(10, 400)
                        .in("[" + SYS + ",{\"role\":\"user\",\"content\":\"where is order 42\"}]")
                        .out("[{\"role\":\"assistant\",\"content\":\"Order 42 ships tomorrow.\"}]")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("T/l1#user:0 where is order 42", "T/l1#reply:0 Order 42 ships tomorrow."),
                messages(timeline),
                "the agent's call arguments read as the person's message and its return object as the reply");
    }

    @Test
    void aTraceWhoseTopAgentHasNotArrivedGivesNoMessageUntilItDoes() {
        String where = "{\"role\":\"user\",\"content\":\"where is order 42\"}";
        TimelineSpan a1 =
                span("T", "a1", "agent").at(0, 1000).in("[" + where + "]").build();
        TimelineSpan l1 = span("T", "l1", "llm")
                .parent("a1")
                .at(100, 190)
                .in("[" + SYS + "," + where + "]")
                .out("[" + TC1 + "]")
                .build();
        TimelineSpan s1 = span("T", "s1", "agent").parent("a1").at(200, 600).build();
        TimelineSpan l2 = span("T", "l2", "llm")
                .parent("s1")
                .at(250, 550)
                .in("[{\"role\":\"user\",\"content\":\"find shipping docs for order 42\"}]")
                .out("internal: carrier is DHL")
                .build();
        TimelineSpan l3 = span("T", "l3", "llm")
                .parent("a1")
                .at(700, 950)
                .in("[" + SYS + "," + where + "," + TC1
                        + ",{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"DHL\"}]")
                .out("Order 42 ships via DHL tomorrow.")
                .build();

        SessionTimeline inFlight = SessionTimelineBuilder.assemble(
                "p1", "s", SessionTimelineBuilder.derive(List.of(l1, s1, l2, l3), false), false);
        SessionTimeline arrived = SessionTimelineBuilder.assemble(
                "p1", "s", SessionTimelineBuilder.derive(List.of(l1, s1, l2, l3, a1), false), false);

        assertEquals(
                List.of("T/l1", "T/l2", "T/s1", "T/l3"),
                inFlight.events().stream().map(Event::id).toList(),
                "a sub-agent whose top agent is not loaded yet reads as the agent the person talks to");
        assertEquals(
                List.of("T/a1#user:0 where is order 42", "T/l3#reply:0 Order 42 ships via DHL tomorrow."),
                messages(arrived));
    }

    @Test
    void aSettledTraceWhoseRootIsNotStoredStillHasItsMessages() {
        List<TimelineSpan> spans = List.of(span("tr-x", "x1", "llm")
                .parent("upstream")
                .at(0, 100)
                .in("[{\"role\":\"user\",\"content\":\"hi\"}]")
                .out("hello")
                .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("tr-x/x1#user:0 hi", "tr-x/x1#reply:0 hello"),
                messages(timeline),
                "a turn whose parent span lives in a service that does not export loses the person's words");
    }

    @Test
    void theAgentOutputsHistoryIsNotANewReply() {
        String q1 = "{\"role\":\"user\",\"content\":\"where is order 42?\"}";
        String r1 = "{\"role\":\"assistant\",\"content\":\"It ships tomorrow.\"}";
        String q2 = "{\"role\":\"user\",\"content\":\"thanks, expedite it\"}";
        String r2 = "{\"role\":\"assistant\",\"content\":\"Done.\"}";
        List<TimelineSpan> spans = List.of(
                span("t1", "a1", "agent")
                        .at(0, 100)
                        .in("[" + q1 + "]")
                        .out("[" + q1 + "," + r1 + "]")
                        .build(),
                span("t2", "b1", "agent")
                        .at(200, 300)
                        .in("[" + q1 + "," + r1 + "," + q2 + "]")
                        .out("[" + q1 + "," + r1 + "," + q2 + "," + r2 + "]")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of(
                        "t1/a1#user:0 where is order 42?",
                        "t1/a1#reply:0 It ships tomorrow.",
                        "t2/b1#user:0 thanks, expedite it",
                        "t2/b1#reply:0 Done."),
                messages(timeline),
                "an agent that returns the whole chat gives the last turn's reply again in this turn");
    }

    @Test
    void anLlmOutputThatHoldsTheWholeChatKeepsOnlyThisCallsOutput() {
        String toolResult = "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"ships tomorrow\"}";
        String done = "{\"role\":\"assistant\",\"content\":\"Done.\"}";
        String history = Q1 + "," + TC1 + "," + toolResult + "," + R1 + "," + EXPEDITE;
        List<TimelineSpan> spans = List.of(span("t2", "l1", "llm")
                .at(0, 100)
                .in("[" + history + "]")
                .out("[" + history + "," + done + "]")
                .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        Event llm = timeline.events().stream()
                .filter(e -> e.kind().equals("llm"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                json("{\"text\":\"Done.\",\"tool_calls\":[],\"reasoning\":null}"),
                json(llm.data().get("output").toString()),
                "an output that holds the whole chat gives the llm event the replies and tool calls of earlier turns");
        assertEquals(List.of("t2/l1#user:0 can you expedite it?", "t2/l1#reply:0 Done."), messages(timeline));
    }

    @Test
    void theAgentLoopsRepeatedTextIsTwoReplies() {
        String u = "{\"role\":\"user\",\"content\":\"expedite 42\"}";
        String first = "{\"role\":\"assistant\",\"content\":\"Let me check.\",\"tool_calls\":[{\"id\":\"c1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"expedite\",\"arguments\":\"{}\"}}]}";
        String second = "{\"role\":\"assistant\",\"content\":\"Let me check.\",\"tool_calls\":[{\"id\":\"c2\","
                + "\"type\":\"function\",\"function\":{\"name\":\"status\",\"arguments\":\"{}\"}}]}";
        String t1 = "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"queued\"}";
        String t2 = "{\"role\":\"tool\",\"tool_call_id\":\"c2\",\"content\":\"done\"}";
        List<TimelineSpan> spans = List.of(
                span("t", "g1", "agent").at(0, 500).out("Done.").build(),
                span("t", "g2", "llm")
                        .parent("g1")
                        .at(10, 100)
                        .in("[" + u + "]")
                        .out("[" + first + "]")
                        .build(),
                span("t", "g4", "llm")
                        .parent("g1")
                        .at(200, 300)
                        .in("[" + u + "," + first + "," + t1 + "]")
                        .out("[" + second + "]")
                        .build(),
                span("t", "g6", "llm")
                        .parent("g1")
                        .at(400, 490)
                        .in("[" + u + "," + first + "," + t1 + "," + second + "," + t2 + "]")
                        .out("Done.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("t/g2#reply:0", "t/g4#reply:0", "t/g6#reply:0"),
                replyIds(timeline),
                "a second message with the same text is taken for the first one");
    }

    @Test
    void aPersonWhoSaysTheSameWordsTwiceInOneTurnGivesTwoMessages() {
        String yes = "{\"role\":\"user\",\"content\":\"yes\"}";
        String ask = "{\"role\":\"assistant\",\"content\":\"Delete all 3 files, ok?\"}";
        List<TimelineSpan> spans = List.of(
                span("T", "l1", "llm")
                        .at(0, 100)
                        .in("[" + SYS + "," + yes + "]")
                        .out("[" + ask + "]")
                        .build(),
                span("T", "l2", "llm")
                        .at(200, 300)
                        .in("[" + SYS + "," + yes + "," + ask + "," + yes + "]")
                        .out("Deleted.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of(
                        "T/l1#user:0 yes",
                        "T/l1#reply:0 Delete all 3 files, ok?",
                        "T/l2#user:0 yes",
                        "T/l2#reply:0 Deleted."),
                messages(timeline),
                "an answer the person gave after a reply of this turn is dropped because an earlier message had its text");
    }

    @Test
    void anAgentThatCarriesOnlyTheNewMessageDoesNotCountItTwice() {
        String yes = "{\"role\":\"user\",\"content\":\"yes\"}";
        String sure = "{\"role\":\"assistant\",\"content\":\"Sure?\"}";
        List<TimelineSpan> spans = List.of(
                span("T2", "b1", "agent")
                        .at(0, 500)
                        .in("[" + yes + "]")
                        .out("Done.")
                        .build(),
                span("T2", "b2", "llm")
                        .parent("b1")
                        .at(10, 490)
                        .in("[" + SYS + "," + yes + "," + sure + "," + yes + "]")
                        .out("Done.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("T2/b1#user:0 yes", "T2/b2#reply:0 Done."),
                messages(timeline),
                "the history of an earlier turn makes the model call's copy of the message a second message");
    }

    @Test
    void aTurnThatStartsWhenThePreviousEndsComesAfterIt() {
        String q1 = "{\"role\":\"user\",\"content\":\"where is order 42?\"}";
        String r1 = "{\"role\":\"assistant\",\"content\":\"It ships tomorrow.\"}";
        String q2 = "{\"role\":\"user\",\"content\":\"expedite it\"}";
        List<TimelineSpan> spans = List.of(
                span("t1", "a1", "agent")
                        .at(0, 100)
                        .in("[" + q1 + "]")
                        .out("[" + r1 + "]")
                        .build(),
                span("t2", "b1", "agent")
                        .at(100, 300)
                        .in("[" + q1 + "," + r1 + "," + q2 + "]")
                        .out("Done.")
                        .build());

        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "s", spans);

        assertEquals(
                List.of("t1/a1#user:0", "t1/a1", "t1/a1#reply:0", "t2/b1#user:0", "t2/b1", "t2/b1#reply:0"),
                timeline.events().stream().map(Event::id).toList(),
                "the next turn's message, one level deeper, sorts before the turn that ended in its instant");
    }

    @Test
    void anEventsBytesIsTheSizeOfItsJsonWithoutPositionAndBytes() {
        SessionTimeline timeline = SessionTimelineBuilder.build("p1", "sess-1", acceptanceSession());

        for (Event event : timeline.events()) {
            JsonNode node = MAPPER.valueToTree(event);
            ((ObjectNode) node).remove(List.of("position", "bytes"));
            assertEquals(
                    node.toString().getBytes(StandardCharsets.UTF_8).length,
                    event.bytes(),
                    event.id() + ": a field of the event is left out of its size");
        }
    }

    /** Section 5.1 of the CC-01 plan: two turns, a nested billing agent, a resent history, a reply on an agent. */
    static List<TimelineSpan> acceptanceSession() {
        return List.of(
                span("tr-a", "a1", "agent")
                        .name("support")
                        .callSite("cs-agent")
                        .at(0, 900)
                        .in("[" + Q1 + "]")
                        .out("[" + R1 + "]")
                        .build(),
                span("tr-a", "a2", "llm")
                        .parent("a1")
                        .callSite("cs-llm")
                        .at(10, 200)
                        .model("gpt-x")
                        .usage(50L, 10L)
                        .in("[" + SYS + "," + Q1 + "]")
                        .out("[" + TC1 + "]")
                        .build(),
                span("tr-a", "a3", "tool")
                        .parent("a1")
                        .name("execute_tool")
                        .callSite("cs-turn-a")
                        .status("ok")
                        .at(210, 300)
                        .tool("lookup_order", "{\"id\":42}", "{\"eta\":\"tomorrow\"}")
                        .build(),
                span("tr-a", "a4", "agent")
                        .parent("a1")
                        .name("billing")
                        .callSite("cs-billing")
                        .at(310, 600)
                        .in("[{\"role\":\"user\",\"content\":\"check refund status for 42\"}]")
                        .out("[{\"role\":\"assistant\",\"content\":\"no refund pending\"}]")
                        .build(),
                span("tr-a", "a5", "llm")
                        .parent("a4")
                        .callSite("cs-billing-llm")
                        .at(320, 590)
                        .in("[{\"role\":\"system\",\"content\":\"You are billing.\"},"
                                + "{\"role\":\"user\",\"content\":\"check refund status for 42\"}]")
                        .out("[{\"role\":\"assistant\",\"content\":\"no refund pending\"}]")
                        .build(),
                span("tr-a", "a6", "llm")
                        .parent("a1")
                        .callSite("cs-llm")
                        .at(610, 900)
                        .in("[" + SYS + "," + Q1 + "," + TC1
                                + ",{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"{\\\"eta\\\":\\\"tomorrow\\\"}\"},"
                                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c2\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"billing\",\"arguments\":\"{}\"}}]},"
                                + "{\"role\":\"tool\",\"tool_call_id\":\"c2\",\"content\":\"no refund pending\"}]")
                        .out("[" + R1 + "]")
                        .build(),
                span("tr-b", "b1", "agent")
                        .name("support")
                        .callSite("cs-agent")
                        .at(1000, 1500)
                        .in("[" + Q1 + "," + R1 + "," + EXPEDITE + "]")
                        .out("Yes, I expedited order 42.")
                        .build(),
                span("tr-b", "b4", "step")
                        .parent("b1")
                        .name("stt")
                        .callSite("cs-turn-b")
                        .at(1001, 1005)
                        .build(),
                span("tr-b", "b2", "llm")
                        .parent("b1")
                        .callSite("cs-llm")
                        .at(1010, 1400)
                        .in("[" + SYS + "," + Q1 + "," + R1 + "," + EXPEDITE + "]")
                        .out("[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c3\","
                                + "\"type\":\"function\",\"function\":{\"name\":\"expedite\","
                                + "\"arguments\":\"{\\\"id\\\":42}\"}}]}]")
                        .build(),
                span("tr-b", "b3", "mcp")
                        .parent("b1")
                        .name("execute_tool")
                        .callSite("cs-turn-b")
                        .status("error")
                        .errorType("timeout")
                        .at(1410, 1450)
                        .tool("expedite", "{\"id\":42}", null)
                        .build());
    }

    private static Instant at(long ms) {
        return T0.plusMillis(ms);
    }

    private static List<Row> rows(SessionTimeline timeline) {
        return timeline.events().stream()
                .map(e -> new Row(
                        e.id(),
                        e.kind(),
                        e.position(),
                        e.at(),
                        e.depth(),
                        e.parentId(),
                        e.parentKind(),
                        e.ancestorKinds(),
                        e.agentId(),
                        e.turn(),
                        e.callSiteId()))
                .toList();
    }

    /** Each message event as {@code "<id> <text>"}, in timeline order. */
    private static List<String> messages(SessionTimeline timeline) {
        return timeline.events().stream()
                .filter(e -> e.kind().equals(Event.USER_MESSAGE) || e.kind().equals(Event.ASSISTANT_REPLY))
                .map(e -> e.id() + " " + e.data().get("text").asText())
                .toList();
    }

    private static List<String> replyIds(SessionTimeline timeline) {
        return timeline.events().stream()
                .filter(e -> e.kind().equals(Event.ASSISTANT_REPLY))
                .map(Event::id)
                .toList();
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

    private static SpanBuilder span(String traceId, String spanId, String kind) {
        return new SpanBuilder(traceId, spanId, kind);
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

        SpanBuilder parent(String v) {
            this.parentSpanId = v;
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

        SpanBuilder at(long startMs, @Nullable Integer endMs) {
            this.startedAt = SessionTimelineBuilderTest.at(startMs);
            this.endedAt = endMs == null ? null : SessionTimelineBuilderTest.at(endMs);
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

        SpanBuilder in(String v) {
            this.input = v;
            return this;
        }

        SpanBuilder out(String v) {
            this.output = v;
            return this;
        }

        SpanBuilder tool(String toolName, String arguments, @Nullable String result) {
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
