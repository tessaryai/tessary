// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @Autowired
    SpanBatchWriter writer;

    private SubstrateV2Fixtures fx;
    private String pid;
    private Instant t0;

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

        assertEquals(
                """
                trA/a1#user:0  user_message     0    0 1 a1 agent agent       a1 trA cs-agent
                trA/a2         llm              1  200 1 a1 agent agent       a1 trA cs-llm
                trA/a3         tool             2  300 1 a1 agent agent       a1 trA cs-turn-a
                trA/a5         llm              3  590 2 a4 agent agent,agent a4 trA cs-billing-llm
                trA/a4         agent            4  600 1 a1 agent agent       a1 trA cs-billing
                trA/a6         llm              5  900 1 a1 agent agent       a1 trA cs-llm
                trA/a6#reply:0 assistant_reply  6  900 2 a6 llm   agent,llm   a1 trA cs-llm
                trA/a1         agent            7  900 0 -  -     -           -  trA cs-agent
                trB/b1#user:0  user_message     8 1000 1 b1 agent agent       b1 trB cs-agent
                trB/b4         step             9 1005 1 b1 agent agent       b1 trB cs-turn-b
                trB/b2         llm             10 1400 1 b1 agent agent       b1 trB cs-llm
                trB/b3         tool            11 1450 1 b1 agent agent       b1 trB cs-turn-b
                trB/b1         agent           12 1500 0 -  -     -           -  trB cs-agent
                trB/b1#reply:0 assistant_reply 13 1500 1 b1 agent agent       b1 trB cs-agent
                """,
                table(timeline, s),
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
        fx.settle(pid, s.trA(), at(900));
        fx.settle(pid, s.trB(), at(1500));
        SessionTimeline full = timelines.load(pid, s.sessionId());
        List<Event> newest = full.events().subList(9, 14);
        long budget = newest.stream().mapToLong(Event::bytes).sum();

        SessionTimeline tail = timelines.loadTail(pid, s.sessionId(), budget);

        assertEquals(
                newest.stream().map(Event::id).toList(),
                tail.events().stream().map(Event::id).toList(),
                "a tail that loads the oldest traces, or ignores the budget");
        assertEquals(
                List.of(0, 1, 2, 3, 4),
                tail.events().stream().map(Event::position).toList());
        assertEquals(true, tail.truncated(), "the first turn was left out, so the tail is truncated");
    }

    @Test
    void loadTailReadsATurnThatEndsAfterItsSubAgent() {
        String sessionId = SubstrateV2Fixtures.sessionId();
        String parent = SubstrateV2Fixtures.traceId();
        String child = SubstrateV2Fixtures.traceId();
        String p1 = SubstrateV2Fixtures.spanId();
        fx.spanSeed(pid)
                .traceId(parent)
                .spanId(p1)
                .sessionId(sessionId)
                .kind("agent")
                .name("support")
                .at(at(0))
                .endedAt(at(1000))
                .payload("[" + Q1 + "]", "All three checks passed.")
                .write();
        List<String> tools = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = SubstrateV2Fixtures.spanId();
            fx.spanSeed(pid)
                    .traceId(child)
                    .spanId(id)
                    .sessionId(sessionId)
                    .kind("tool")
                    .name("check")
                    .at(at(100 + i * 200L))
                    .endedAt(at(200 + i * 200L))
                    .payload(i == 0 ? "\"" + "x".repeat(4000) + "\"" : "\"ok\"", null)
                    .write();
            tools.add(child + "/" + id);
        }
        fx.detachUnder(pid, child, parent);
        fx.settle(pid, parent, at(1000));
        fx.settle(pid, child, at(600));
        SessionTimeline full = timelines.load(pid, sessionId);
        List<String> newest = List.of(tools.get(1), tools.get(2), parent + "/" + p1, parent + "/" + p1 + "#reply:0");
        assertEquals(
                List.of(
                        parent + "/" + p1 + "#user:0",
                        tools.get(0),
                        newest.get(0),
                        newest.get(1),
                        newest.get(2),
                        newest.get(3)),
                full.events().stream().map(Event::id).toList());
        long budget =
                full.events().subList(2, 6).stream().mapToLong(Event::bytes).sum();
        assertTrue(
                full.events().get(1).bytes()
                        > full.events().get(4).bytes() + full.events().get(5).bytes(),
                "the first check must outweigh the turn's own events, so the sub-agent alone fills the budget");

        SessionTimeline tail = timelines.loadTail(pid, sessionId, budget);

        assertEquals(
                newest,
                tail.events().stream().map(Event::id).toList(),
                "a load in start order reads the sub-agent first, stops there and drops the turn's end and reply");
        assertEquals(
                List.of(0, 1, 2, 3), tail.events().stream().map(Event::position).toList());
        assertEquals(true, tail.truncated());
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

    /**
     * Catches a span whose producer named no operation read as anything but a step, so a pattern for unknown work
     * cannot name it, and a tool event that loses a text result or text arguments: ingest keeps only a JSON object or
     * array in the tool_call row's result and arguments, so the text is in the span payload and in arguments_raw.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        load_profile | {"user":7} | {"plan":"pro"} |      | step | {"name":"load_profile","input":{"user":7},"output":{"plan":"pro"}}
        find_slot    | friday     | free at 8      | tool | tool | {"name":"find_slot","mcp":false,"status":null,"error_type":null,"input":"friday","output":"free at 8"}
        """)
    void anIngestedSpanReachesItsEventAsAStepOrAToolWithItsTextKept(
            String name, String input, String output, @Nullable String kind, String eventKind, String data) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        Map<String, Object> attributes = kind == null
                ? Map.of(GenAiAttributes.SESSION_ID, sessionId)
                : Map.of(GenAiAttributes.SESSION_ID, sessionId, GenAiAttributes.TOOL_NAME, name);
        writer.write(
                pid,
                List.of(new RawEntry(
                        "s1",
                        name,
                        input,
                        output,
                        null,
                        attributes,
                        null,
                        "tr-" + UUID.randomUUID(),
                        at(0).toString(),
                        kind,
                        at(40).toString(),
                        null,
                        null)));

        SessionTimeline timeline = timelines.load(pid, sessionId);

        assertEquals(
                List.of(eventKind), timeline.events().stream().map(Event::kind).toList());
        assertEquals(List.of(json(data)), data(timeline));
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

    /**
     * One line per event: id, kind, position, milliseconds after t0, depth, parent and its kind, ancestor kinds, agent,
     * turn and call site, with every seeded id written as its name.
     */
    private String table(SessionTimeline timeline, Seeded s) {
        return timeline.events().stream()
                .map(e -> String.format(
                        Locale.ROOT,
                        "%-14s %-15s %2d %4d %d %-2s %-5s %-11s %-2s %s %s",
                        named(e.id(), s),
                        e.kind(),
                        e.position(),
                        Duration.between(t0, e.at()).toMillis(),
                        e.depth(),
                        named(dash(e.parentId()), s),
                        dash(e.parentKind()),
                        e.ancestorKinds().isEmpty() ? "-" : String.join(",", e.ancestorKinds()),
                        named(dash(e.agentId()), s),
                        named(e.turn(), s),
                        named(dash(e.callSiteId()), s)))
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static String named(String line, Seeded s) {
        String named = line.replace(s.trA(), "trA").replace(s.trB(), "trB");
        for (Map.Entry<String, String> id : s.ids().entrySet()) {
            named = named.replace(id.getValue(), id.getKey());
        }
        return named;
    }

    private static String dash(@Nullable String value) {
        return value == null ? "-" : value;
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
