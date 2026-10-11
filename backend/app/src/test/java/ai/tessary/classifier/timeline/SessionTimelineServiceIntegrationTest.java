// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link SessionTimelineService} loads a session from Postgres as one timeline: the session is {@code session_id}, a
 * trace of another session that shares the {@code thread_id} is not in it, and the rows map to the events the builder
 * derives (the call site falls back to the trace, a tool's name and arguments come from its {@code tool_call}).
 */
@SpringBootTest
class SessionTimelineServiceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYS = "{\"role\":\"system\",\"content\":\"You are support.\"}";
    private static final String Q1 = "{\"role\":\"user\",\"content\":\"where is my order 42?\"}";
    private static final String C1 = "{\"id\":\"c1\",\"type\":\"function\","
            + "\"function\":{\"name\":\"lookup_order\",\"arguments\":\"{\\\"id\\\":42}\"}}";
    private static final String TC1 = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + C1 + "]}";
    private static final String R1 = "{\"role\":\"assistant\",\"content\":\"Order 42 ships tomorrow.\"}";
    private static final String EXPEDITE = "{\"role\":\"user\",\"content\":\"can you expedite it?\"}";

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SessionTimelineService timelines;

    private SubstrateV2Fixtures fx;
    private String pid;
    private Instant t0;

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

    /** The seeded acceptance session: its id and the span id each fixture name got. */
    private record Seeded(String sessionId, String trA, String trB, Map<String, String> ids) {
        String id(String name) {
            return Objects.requireNonNull(ids.get(name), name);
        }
    }

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        pid = TenantFixture.bootstrap(tenants, "session-timeline").project().id();
        t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void loadsTheSessionAsOneTimeline() {
        Seeded s = seedAcceptanceSession();

        SessionTimeline timeline = timelines.load(pid, s.sessionId());

        String trA = s.trA();
        String trB = s.trB();
        assertEquals(
                List.of(
                        new Row(
                                trA + "/" + s.id("a1") + "#user:0",
                                "user_message",
                                0,
                                at(0),
                                1,
                                s.id("a1"),
                                "agent",
                                List.of("agent"),
                                s.id("a1"),
                                trA,
                                "cs-agent"),
                        new Row(
                                trA + "/" + s.id("a2"),
                                "llm",
                                1,
                                at(200),
                                1,
                                s.id("a1"),
                                "agent",
                                List.of("agent"),
                                s.id("a1"),
                                trA,
                                "cs-llm"),
                        new Row(
                                trA + "/" + s.id("a3"),
                                "tool",
                                2,
                                at(300),
                                1,
                                s.id("a1"),
                                "agent",
                                List.of("agent"),
                                s.id("a1"),
                                trA,
                                "cs-turn-a"),
                        new Row(
                                trA + "/" + s.id("a5"),
                                "llm",
                                3,
                                at(590),
                                2,
                                s.id("a4"),
                                "agent",
                                List.of("agent", "agent"),
                                s.id("a4"),
                                trA,
                                "cs-billing-llm"),
                        new Row(
                                trA + "/" + s.id("a4"),
                                "agent",
                                4,
                                at(600),
                                1,
                                s.id("a1"),
                                "agent",
                                List.of("agent"),
                                s.id("a1"),
                                trA,
                                "cs-billing"),
                        new Row(
                                trA + "/" + s.id("a6"),
                                "llm",
                                5,
                                at(900),
                                1,
                                s.id("a1"),
                                "agent",
                                List.of("agent"),
                                s.id("a1"),
                                trA,
                                "cs-llm"),
                        new Row(
                                trA + "/" + s.id("a6") + "#reply:0",
                                "assistant_reply",
                                6,
                                at(900),
                                2,
                                s.id("a6"),
                                "llm",
                                List.of("agent", "llm"),
                                s.id("a1"),
                                trA,
                                "cs-llm"),
                        new Row(
                                trA + "/" + s.id("a1"),
                                "agent",
                                7,
                                at(900),
                                0,
                                null,
                                null,
                                List.of(),
                                null,
                                trA,
                                "cs-agent"),
                        new Row(
                                trB + "/" + s.id("b1") + "#user:0",
                                "user_message",
                                8,
                                at(1000),
                                1,
                                s.id("b1"),
                                "agent",
                                List.of("agent"),
                                s.id("b1"),
                                trB,
                                "cs-agent"),
                        new Row(
                                trB + "/" + s.id("b4"),
                                "step",
                                9,
                                at(1005),
                                1,
                                s.id("b1"),
                                "agent",
                                List.of("agent"),
                                s.id("b1"),
                                trB,
                                "cs-turn-b"),
                        new Row(
                                trB + "/" + s.id("b2"),
                                "llm",
                                10,
                                at(1400),
                                1,
                                s.id("b1"),
                                "agent",
                                List.of("agent"),
                                s.id("b1"),
                                trB,
                                "cs-llm"),
                        new Row(
                                trB + "/" + s.id("b3"),
                                "tool",
                                11,
                                at(1450),
                                1,
                                s.id("b1"),
                                "agent",
                                List.of("agent"),
                                s.id("b1"),
                                trB,
                                "cs-turn-b"),
                        new Row(
                                trB + "/" + s.id("b1"),
                                "agent",
                                12,
                                at(1500),
                                0,
                                null,
                                null,
                                List.of(),
                                null,
                                trB,
                                "cs-agent"),
                        new Row(
                                trB + "/" + s.id("b1") + "#reply:0",
                                "assistant_reply",
                                13,
                                at(1500),
                                1,
                                s.id("b1"),
                                "agent",
                                List.of("agent"),
                                s.id("b1"),
                                trB,
                                "cs-agent")),
                rows(timeline),
                "a thread-keyed read, a span of another session, or a wrong row mapping changes the timeline");
        assertEquals(false, timeline.truncated());

        List<JsonNode> data = data(timeline);
        assertEquals(json("{\"text\":\"where is my order 42?\",\"from_person\":true}"), data.get(0));
        assertEquals(json("{\"text\":\"can you expedite it?\",\"from_person\":true}"), data.get(8));
        assertEquals(json("\"gpt-x\""), data.get(1).get("model"));
        assertEquals(json("\"You are support.\""), data.get(1).get("system_prompt"));
        assertEquals(json("[" + Q1 + "]"), data.get(1).get("new_messages"));
        assertEquals(
                json("{\"text\":null,\"tool_calls\":[" + C1 + "],\"reasoning\":null}"),
                data.get(1).get("output"));
        assertEquals(json("50"), data.get(1).get("usage").get("input_tokens"));
        assertEquals(json("10"), data.get(1).get("usage").get("output_tokens"));
        assertEquals(json("\"You are billing.\""), data.get(3).get("system_prompt"));
        assertEquals(
                json("[{\"role\":\"user\",\"content\":\"check refund status for 42\"}]"),
                data.get(3).get("new_messages"));
        assertEquals(
                json("[{\"role\":\"tool\",\"tool_call_id\":\"c2\",\"content\":\"no refund pending\"}]"),
                data.get(5).get("new_messages"));
        assertEquals(
                json("\"Order 42 ships tomorrow.\""), data.get(5).get("output").get("text"));
        assertEquals(json("[" + EXPEDITE + "]"), data.get(10).get("new_messages"));
        assertEquals(
                json("{\"name\":\"lookup_order\",\"mcp\":false,\"status\":\"ok\",\"error_type\":null,"
                        + "\"input\":{\"id\":42},\"output\":{\"eta\":\"tomorrow\"}}"),
                data.get(2),
                "the tool's name and arguments are read from its tool_call row");
        assertEquals(
                json("{\"name\":\"expedite\",\"mcp\":true,\"status\":\"error\",\"error_type\":\"timeout\","
                        + "\"input\":{\"id\":42},\"output\":null}"),
                data.get(11));
        assertEquals(json("{\"name\":\"stt\",\"input\":null,\"output\":null}"), data.get(9));
        assertEquals(json("{\"text\":\"Order 42 ships tomorrow.\"}"), data.get(6));
        assertEquals(json("{\"text\":\"Yes, I expedited order 42.\"}"), data.get(13));
    }

    @Test
    void loadTailStopsAtTheBudget() {
        Seeded s = seedAcceptanceSession();
        SessionTimeline full = timelines.load(pid, s.sessionId());
        List<Event> newest = full.events().subList(8, 14);
        long budget = newest.stream().mapToLong(Event::bytes).sum();

        SessionTimeline tail = timelines.loadTail(pid, s.sessionId(), budget);

        assertEquals(
                newest.stream().map(Event::id).toList(),
                tail.events().stream().map(Event::id).toList(),
                "a tail that loads the oldest traces, or ignores the budget");
        assertEquals(
                List.of(0, 1, 2, 3, 4, 5),
                tail.events().stream().map(Event::position).toList());
        assertEquals(true, tail.truncated(), "the first turn was left out, so the tail is truncated");
    }

    @Test
    void loadReadsPastTheFirstPageOfTraces() {
        String sessionId = SubstrateV2Fixtures.sessionId();
        List<String> eventIds = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            SpanRef ref = fx.spanSeed(pid)
                    .sessionId(sessionId)
                    .kind("tool")
                    .name("execute_tool")
                    .at(at(i * 100L))
                    .endedAt(at(i * 100L + 50))
                    .writeRef();
            eventIds.add(ref.traceId() + "/" + ref.spanId());
        }

        SessionTimeline timeline = timelines.load(pid, sessionId);

        assertEquals(
                eventIds,
                timeline.events().stream().map(Event::id).toList(),
                "the cursor between pages of 20 traces repeats or drops a trace, so a long session loses its start");
        assertEquals(false, timeline.truncated());
        assertEquals(
                IntStream.range(0, 21).boxed().toList(),
                timeline.events().stream().map(Event::position).toList());
    }

    /** Section 5.1 of the CC-01 plan, plus a trace of another session that shares tr-a's thread id. */
    private Seeded seedAcceptanceSession() {
        String sessionId = SubstrateV2Fixtures.sessionId();
        String trA = SubstrateV2Fixtures.traceId();
        String trB = SubstrateV2Fixtures.traceId();
        Map<String, String> ids = new HashMap<>();
        for (String name : List.of("a1", "a2", "a3", "a4", "a5", "a6", "b1", "b2", "b3", "b4")) {
            ids.put(name, SubstrateV2Fixtures.spanId());
        }
        Seeded s = new Seeded(sessionId, trA, trB, ids);

        seed(s, trA, "a1", null, "agent", "support", "cs-agent", 0, 900)
                .threadId("thr-shared")
                .payload("[" + Q1 + "]", "[" + R1 + "]")
                .write();
        fx.traceCallSite(pid, trA, "cs-turn-a");
        seed(s, trA, "a2", "a1", "llm", "chat", "cs-llm", 10, 200)
                .model("gpt-x")
                .usage(50L, 10L)
                .payload("[" + SYS + "," + Q1 + "]", "[" + TC1 + "]")
                .write();
        seed(s, trA, "a3", "a1", "tool", "execute_tool", null, 210, 300)
                .status("ok")
                .write();
        fx.toolCall(
                pid,
                new SpanRef(trA, s.id("a3")),
                "lookup_order",
                null,
                "{\"id\":42}",
                "{\"eta\":\"tomorrow\"}",
                at(300));
        seed(s, trA, "a4", "a1", "agent", "billing", "cs-billing", 310, 600)
                .payload(
                        "[{\"role\":\"user\",\"content\":\"check refund status for 42\"}]",
                        "[{\"role\":\"assistant\",\"content\":\"no refund pending\"}]")
                .write();
        seed(s, trA, "a5", "a4", "llm", "chat", "cs-billing-llm", 320, 590)
                .payload(
                        "[{\"role\":\"system\",\"content\":\"You are billing.\"},"
                                + "{\"role\":\"user\",\"content\":\"check refund status for 42\"}]",
                        "[{\"role\":\"assistant\",\"content\":\"no refund pending\"}]")
                .write();
        seed(s, trA, "a6", "a1", "llm", "chat", "cs-llm", 610, 900)
                .payload(
                        "[" + SYS + "," + Q1 + "," + TC1
                                + ",{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"{\\\"eta\\\":\\\"tomorrow\\\"}\"},"
                                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c2\","
                                + "\"type\":\"function\",\"function\":{\"name\":\"billing\",\"arguments\":\"{}\"}}]},"
                                + "{\"role\":\"tool\",\"tool_call_id\":\"c2\",\"content\":\"no refund pending\"}]",
                        "[" + R1 + "]")
                .write();

        seed(s, trB, "b1", null, "agent", "support", "cs-agent", 1000, 1500)
                .payload("[" + Q1 + "," + R1 + "," + EXPEDITE + "]", "Yes, I expedited order 42.")
                .write();
        fx.traceCallSite(pid, trB, "cs-turn-b");
        seed(s, trB, "b4", "b1", "step", "stt", null, 1001, 1005).write();
        seed(s, trB, "b2", "b1", "llm", "chat", "cs-llm", 1010, 1400)
                .payload(
                        "[" + SYS + "," + Q1 + "," + R1 + "," + EXPEDITE + "]",
                        "[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c3\","
                                + "\"type\":\"function\",\"function\":{\"name\":\"expedite\","
                                + "\"arguments\":\"{\\\"id\\\":42}\"}}]}]")
                .write();
        seed(s, trB, "b3", "b1", "mcp", "execute_tool", null, 1410, 1450)
                .status("error")
                .errorType("timeout")
                .write();
        fx.toolCall(pid, new SpanRef(trB, s.id("b3")), "expedite", "timeout", "{\"id\":42}", null, at(1450));

        fx.spanSeed(pid)
                .sessionId(SubstrateV2Fixtures.sessionId())
                .threadId("thr-shared")
                .kind("llm")
                .callSiteId("cs-llm")
                .at(at(2000))
                .endedAt(at(2100))
                .payload("[{\"role\":\"user\",\"content\":\"another conversation\"}]", "sure")
                .write();
        return s;
    }

    private SubstrateV2Fixtures.SpanSeed seed(
            Seeded s,
            String traceId,
            String name,
            @Nullable String parent,
            String kind,
            String spanName,
            @Nullable String callSiteId,
            long startMs,
            long endMs) {
        return fx.spanSeed(pid)
                .traceId(traceId)
                .spanId(s.id(name))
                .parentSpanId(parent == null ? null : s.id(parent))
                .sessionId(s.sessionId())
                .kind(kind)
                .name(spanName)
                .callSiteId(callSiteId)
                .at(at(startMs))
                .endedAt(at(endMs));
    }

    private Instant at(long ms) {
        return t0.plusMillis(ms);
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
}
