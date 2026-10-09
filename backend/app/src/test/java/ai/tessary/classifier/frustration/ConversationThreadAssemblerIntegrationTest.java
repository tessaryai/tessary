// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link ConversationThreadAssembler} walks the whole conversation against real Postgres: user and assistant messages
 * oldest first, the scored message as the current turn, system messages excluded. The conversation is the session,
 * one trace per turn; {@code trace.thread_id} is only a column.
 */
@SpringBootTest
class ConversationThreadAssemblerIntegrationTest {

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    SubstrateReadRepository substrate;

    @Autowired
    ConversationThreadAssembler assembler;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    private static final String CHAT = "cs-chat";

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    @Test
    void assemblesTheWholeSessionThreadExcludingSystem() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-assembly").project().id();

        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        // Turn 0 input carries a system message beside the user turn.
        String withSystem = "[{\"role\":\"system\",\"content\":\"You are a helpful export bot.\"},"
                + "{\"role\":\"user\",\"content\":\"can you export this?\"}]";
        seedTurn(pid, sessionId, base.plusMillis(1_000), withSystem, assistant("Sure, running the export now."));

        seedTurn(pid, sessionId, base.plusMillis(2_000), user("still broken"), assistant("Let me retry."));

        // Turn 2 (scored): no assistant reply yet.
        SpanRef scoredRef = seedTurn(pid, sessionId, base.plusMillis(3_000), user("nevermind"), null);

        SubstrateObservation scored = substrate
                .observationsByIds(
                        pid, List.of(new GroundingEvidenceReads.SpanRef(scoredRef.traceId(), scoredRef.spanId())))
                .get(0);
        StructuredThread thread = assembler.assembleStructured(scored).orElseThrow();

        assertEquals(
                List.of("can you export this?", "Sure, running the export now.", "still broken", "Let me retry."),
                texts(thread),
                "the system prompt is not a message");
        assertEquals("nevermind", thread.current().text());
    }

    @Test
    void groupsAtTheConversationGrainDedupingAgentAndLlmTwins() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-conv-grain").project().id();
        Instant base = Instant.now();

        // Another session carrying the same thread id must not bleed in.
        String sessionId = SubstrateV2Fixtures.sessionId();
        String sibling = SubstrateV2Fixtures.sessionId();
        String c1 = "conv-1";

        seedTwinTurn(
                pid,
                sibling,
                c1,
                base.plusMillis(500),
                user("OTHER CONVERSATION please ignore"),
                assistant("ok"),
                null);

        // Each c1 turn has agent and llm twins with the same delta (rendered once); turn 0 adds a textless tool span.
        seedTwinTurn(pid, sessionId, c1, base.plusMillis(1_000), user("deploy the app"), assistant("On it."), "search");
        seedTwinTurn(pid, sessionId, c1, base.plusMillis(2_000), user("still failing"), assistant("Retrying."), null);
        SpanRef scoredRef = seedTwinTurn(pid, sessionId, c1, base.plusMillis(3_000), user("nevermind"), null, null);

        SubstrateObservation scored = substrate
                .observationsByIds(
                        pid, List.of(new GroundingEvidenceReads.SpanRef(scoredRef.traceId(), scoredRef.spanId())))
                .get(0);
        StructuredThread thread = assembler.assembleStructured(scored).orElseThrow();

        assertEquals(
                List.of("deploy the app", "On it.", "still failing", "Retrying."),
                texts(thread),
                "the sibling session never bleeds in, the agent/llm twins give one message each, and the"
                        + " tool span leaves no text");
        assertEquals("nevermind", thread.current().text());
    }

    /**
     * A producer that sends one thread id per user for all time, {@code whatsapp_<user>}: each session is its own
     * conversation, and two threads inside one session are one conversation. Keyed on the thread, the scored turn's
     * earlier messages came from a session days before.
     */
    @Test
    void aThreadThatSpansTwoSessionsIsTwoConversations() {
        String pid = TenantFixture.bootstrap(tenants, "thread-two-sessions")
                .project()
                .id();
        Instant monday = Instant.now().minus(Duration.ofDays(3));
        Instant thursday = Instant.now();
        String userThread = "whatsapp_u1";
        String mondaySession = SubstrateV2Fixtures.sessionId();
        String thursdaySession = SubstrateV2Fixtures.sessionId();

        seedTwinTurn(pid, mondaySession, userThread, monday, user("book a table"), assistant("Booked."), null);
        seedTwinTurn(
                pid, mondaySession, userThread, monday.plusMillis(1_000), user("for four"), assistant("Done."), null);
        seedTwinTurn(
                pid, thursdaySession, userThread, thursday, user("where is my order"), assistant("Checking."), null);
        seedTwinTurn(
                pid,
                thursdaySession,
                "side-thread",
                thursday.plusMillis(1_000),
                user("still nothing"),
                assistant("Still checking."),
                null);
        SpanRef scoredRef =
                seedTwinTurn(pid, thursdaySession, userThread, thursday.plusMillis(2_000), user("useless"), null, null);

        FrustrationTurnBuilder.EligibleTurn turn = new FrustrationTurnBuilder(assembler)
                .buildTurn(scored(pid, scoredRef))
                .orElseThrow();

        assertEquals(
                List.of(
                        new FrustrationTurnBuilder.EarlierMessage("user", "where is my order"),
                        new FrustrationTurnBuilder.EarlierMessage("assistant", "Checking."),
                        new FrustrationTurnBuilder.EarlierMessage("user", "still nothing"),
                        new FrustrationTurnBuilder.EarlierMessage("assistant", "Still checking.")),
                turn.state().earlierMessages(),
                "Thursday's turns only, across both of its thread ids");
        assertEquals(3, turn.userTurn(), "Monday's turns are another conversation");
    }

    /** One turn: one trace with a root llm span. Returns the span. */
    @Test
    void ordersByWhenTheTurnHappenedNotWhenItWasStored() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-event-order").project().id();
        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        // Stored latest-first, as an upload or batched exporter can deliver.
        seedTurn(pid, sessionId, base.plusMillis(4_000), user("a later question"), assistant("A later answer."));
        SpanRef scoredRef = seedTurn(pid, sessionId, base.plusMillis(3_000), user("nevermind"), null);
        seedTurn(pid, sessionId, base.plusMillis(2_000), user("still broken"), assistant("Let me retry."));
        seedTurn(pid, sessionId, base.plusMillis(1_000), user("can you export this?"), assistant("Sure."));

        SubstrateObservation scored = substrate
                .observationsByIds(
                        pid, List.of(new GroundingEvidenceReads.SpanRef(scoredRef.traceId(), scoredRef.spanId())))
                .get(0);
        StructuredThread thread = assembler.assembleStructured(scored).orElseThrow();

        assertEquals(
                List.of("can you export this?", "Sure.", "still broken", "Let me retry."),
                thread.earlier().stream().map(StructuredThread.Message::text).toList(),
                "turns stored after the scored one but started before it are earlier; the later turn is not");
        assertEquals("nevermind", thread.current().text());
    }

    @Test
    void aTurnThatRanManyToolsStillLeavesItsMessages() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-busy-turn").project().id();
        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        seedTurn(pid, sessionId, base.plusMillis(1_000), user("deploy the app"), assistant("On it."));
        String busy = SubstrateV2Fixtures.traceId();
        turn(pid, busy, sessionId, base.plusMillis(2_000), user("still failing"), assistant("Fixed it."));
        for (int i = 1; i <= 45; i++) {
            fx.spanSeed(pid)
                    .traceId(busy)
                    .sessionId(sessionId)
                    .kind("tool")
                    .name("search")
                    .at(base.plusMillis(2_000 + i))
                    .write();
        }
        SpanRef scoredRef = seedTurn(pid, sessionId, base.plusMillis(3_000), user("nevermind"), null);

        StructuredThread thread =
                assembler.assembleStructured(scored(pid, scoredRef)).orElseThrow();

        assertEquals(List.of("deploy the app", "On it.", "still failing", "Fixed it."), texts(thread));
    }

    @Test
    void countsEveryEarlierTurnOfALongConversation() {
        String pid = TenantFixture.bootstrap(tenants, "thread-long").project().id();
        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        for (int i = 1; i <= 50; i++) {
            seedTurn(pid, sessionId, base.plusMillis(i * 1_000L), user("question " + i), assistant("answer " + i));
        }
        SpanRef scoredRef = seedTurn(pid, sessionId, base.plusMillis(51_000), user("nevermind"), null);

        FrustrationTurnBuilder.EligibleTurn turn = new FrustrationTurnBuilder(assembler)
                .buildTurn(scored(pid, scoredRef))
                .orElseThrow();

        assertEquals(51, turn.userTurn());
    }

    @Test
    void aSubAgentTraceIsNotPartOfTheReply() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-sub-agent").project().id();
        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        seedTurn(pid, sessionId, base.plusMillis(1_000), user("deploy the app"), assistant("On it."));
        SpanRef parent =
                seedTurn(pid, sessionId, base.plusMillis(2_000), user("still failing"), assistant("Let me retry."));
        String child = SubstrateV2Fixtures.traceId();
        turn(pid, child, sessionId, base.plusMillis(2_500), null, assistant("sub-agent notes"));
        jdbc.sql("UPDATE trace SET parent_trace_id = :parent WHERE project_id = :pid AND id = :child")
                .param("parent", parent.traceId())
                .param("pid", pid)
                .param("child", child)
                .update();
        SpanRef scoredRef = seedTurn(pid, sessionId, base.plusMillis(3_000), user("nevermind"), null);

        StructuredThread thread =
                assembler.assembleStructured(scored(pid, scoredRef)).orElseThrow();

        assertEquals(List.of("deploy the app", "On it.", "still failing", "Let me retry."), texts(thread));
    }

    @Test
    void aReplyWithNoUserMessageBeforeItMakesTheTurnIneligible() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-no-user").project().id();
        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        seedTurn(pid, sessionId, base.plusMillis(1_000), user("deploy the app"), assistant("On it."));
        seedTurn(pid, sessionId, base.plusMillis(2_000), user("still failing"), assistant("Let me retry."));
        turn(pid, SubstrateV2Fixtures.traceId(), sessionId, base.plusMillis(2_500), null, assistant("Also this."));
        SpanRef scoredRef = seedTurn(pid, sessionId, base.plusMillis(3_000), user("nevermind"), null);

        assertTrue(new FrustrationTurnBuilder(assembler)
                .buildTurn(scored(pid, scoredRef))
                .isEmpty());
    }

    /**
     * A turn that calls a router, the reply and a memory pass, each its own call site: the thread is the reply's. Read
     * across call sites, each earlier turn's user message is the router's prompt and its reply joins the router's and
     * the memory pass's JSON; a turn that never reached the reply still counts as one of its turns.
     */
    @Test
    void theThreadIsReadFromTheScoredCallSiteOnly() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-call-site").project().id();
        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        seedRoutedTurn(pid, sessionId, base.plusMillis(1_000), "it still fails", "Sorry, I will look into it.");
        String background = SubstrateV2Fixtures.traceId();
        fx.spanSeed(pid)
                .traceId(background)
                .sessionId(sessionId)
                .callSiteId("cs-memory")
                .at(base.plusMillis(1_500))
                .payload(user("summarize the user"), assistant("{\\\"notes\\\":[]}"))
                .write();
        seedRoutedTurn(pid, sessionId, base.plusMillis(2_000), "same error again", "Please clear the cache and retry.");
        SpanRef scoredRef = seedRoutedTurn(pid, sessionId, base.plusMillis(3_000), "this is useless", null);

        FrustrationTurnBuilder.EligibleTurn turn = new FrustrationTurnBuilder(assembler)
                .buildTurn(scored(pid, scoredRef))
                .orElseThrow();

        assertEquals(
                new FrustrationTurnBuilder.TurnState(
                        "this is useless",
                        List.of(
                                new FrustrationTurnBuilder.EarlierMessage("user", "it still fails"),
                                new FrustrationTurnBuilder.EarlierMessage("assistant", "Sorry, I will look into it."),
                                new FrustrationTurnBuilder.EarlierMessage("user", "same error again"),
                                new FrustrationTurnBuilder.EarlierMessage(
                                        "assistant", "Please clear the cache and retry."))),
                turn.state());
        assertEquals(3, turn.userTurn(), "the memory-only turn is not one of the reply's turns");
    }

    /**
     * One turn of a routed chat app: a router call, the reply on {@link #CHAT}, then a memory pass, under one trace.
     * Returns the reply span.
     */
    private SpanRef seedRoutedTurn(
            String pid,
            String sessionId,
            Instant at,
            String userText,
            @org.jspecify.annotations.Nullable String replyText) {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .callSiteId("cs-router")
                .at(at)
                .payload(
                        user("recent messages: user: " + userText + " output JSON:"),
                        assistant("{\\\"lane\\\":\\\"SUPPORT\\\"}"))
                .write();
        SpanRef reply = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .callSiteId(CHAT)
                .at(at.plusMillis(1))
                .payload(user(userText), replyText == null ? null : assistant(replyText))
                .writeRef();
        fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .callSiteId("cs-memory")
                .at(at.plusMillis(2))
                .payload(user("update memory"), assistant("{\\\"tone\\\":\\\"annoyed\\\"}"))
                .write();
        return reply;
    }

    private SubstrateObservation scored(String pid, SpanRef ref) {
        return substrate
                .observationsByIds(pid, List.of(new GroundingEvidenceReads.SpanRef(ref.traceId(), ref.spanId())))
                .get(0);
    }

    private SpanRef seedTurn(
            String pid, String sessionId, Instant at, String input, @org.jspecify.annotations.Nullable String output) {
        return turn(pid, SubstrateV2Fixtures.traceId(), sessionId, at, input, output);
    }

    /** One turn: one trace with a root {@code llm} span on {@link #CHAT}. Returns the span. */
    private SpanRef turn(
            String pid,
            String traceId,
            String sessionId,
            Instant at,
            @org.jspecify.annotations.Nullable String input,
            @org.jspecify.annotations.Nullable String output) {
        return fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .callSiteId(CHAT)
                .at(at)
                .payload(input, output)
                .writeRef();
    }

    /**
     * One turn of {@code threadId} with agent and llm twins (and an optional tool span) on one trace; returns the llm
     * span. Written in that order since the spans share a start and ties break on insert time.
     */
    private SpanRef seedTwinTurn(
            String pid,
            String sessionId,
            String threadId,
            Instant at,
            String userInput,
            @org.jspecify.annotations.Nullable String assistantOut,
            @org.jspecify.annotations.Nullable String toolName) {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(pid, traceId, sessionId, threadId, null, at);
        fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .threadId(threadId)
                .callSiteId(CHAT)
                .kind("agent")
                .at(at)
                .payload(userInput, assistantOut)
                .write();
        SpanRef llm = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .threadId(threadId)
                .callSiteId(CHAT)
                .kind("llm")
                .model("gpt-x")
                .at(at.plusMillis(1))
                .payload(userInput, assistantOut)
                .writeRef();
        if (toolName != null) {
            fx.spanSeed(pid)
                    .traceId(traceId)
                    .sessionId(sessionId)
                    .threadId(threadId)
                    .kind("tool")
                    .name(toolName)
                    .at(at.plusMillis(2))
                    .write();
        }
        return llm;
    }

    private static List<String> texts(StructuredThread thread) {
        return thread.earlier().stream().map(StructuredThread.Message::text).toList();
    }

    private static String user(String text) {
        return "[{\"role\":\"user\",\"content\":\"" + text + "\"}]";
    }

    private static String assistant(String text) {
        return "[{\"role\":\"assistant\",\"content\":\"" + text + "\"}]";
    }
}
