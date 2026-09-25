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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DB-backed proof that {@link ConversationThreadAssembler} walks a whole conversation, not just the
 * scored row: user and assistant messages oldest first, the scored user message as the current turn,
 * system messages excluded. Runs against the real pgvector Postgres (Testcontainers) so the
 * conversation grouping and keyset ordering execute for real.
 *
 * <p>A turn is one trace, and the conversation is {@code COALESCE(trace.thread_id, trace.session_id)},
 * so a turn's position in the thread is its keyset position.
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

        // Turn 0: input carries a SYSTEM message alongside the user turn; assistant replies.
        String withSystem = "[{\"role\":\"system\",\"content\":\"You are a helpful export bot.\"},"
                + "{\"role\":\"user\",\"content\":\"can you export this?\"}]";
        seedTurn(pid, sessionId, base.plusMillis(1_000), withSystem, assistant("Sure, running the export now."));

        // Turn 1: plain user + assistant.
        seedTurn(pid, sessionId, base.plusMillis(2_000), user("still broken"), assistant("Let me retry."));

        // Turn 2 (scored): the user's terse latest message, no assistant reply yet.
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

        // One session carrying two conversations. In v2 that is two thread_ids on the traces, not two
        // tiers of a tree: the thread must be scoped to the scored turn's conversation (c1), and c2 —
        // a sibling under the SAME session — must not bleed in.
        String sessionId = SubstrateV2Fixtures.sessionId();
        String c1 = "conv-1";
        String c2 = "conv-2";

        seedTwinTurn(
                pid,
                sessionId,
                c2,
                base.plusMillis(500),
                user("OTHER CONVERSATION please ignore"),
                assistant("ok"),
                null);

        // c1: each turn carries BOTH an agent and an llm span bearing the same delta (must render ONCE),
        // turn 0 also has a tool span, which leaves no text.
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
                "the sibling conversation never bleeds in, the agent/llm twins give one message each, and the"
                        + " tool span leaves no text");
        assertEquals("nevermind", thread.current().text());
    }

    /** One turn = one trace with a single root llm span carrying the dialogue. Returns the span. */
    @Test
    void ordersByWhenTheTurnHappenedNotWhenItWasStored() {
        String pid =
                TenantFixture.bootstrap(tenants, "thread-event-order").project().id();
        Instant base = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();

        // Stored latest-first, as an upload or a batched exporter can deliver them.
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
        fx.turn(pid, busy, sessionId, base.plusMillis(2_000), user("still failing"), assistant("Fixed it."));
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
        fx.turn(pid, child, sessionId, base.plusMillis(2_500), null, assistant("sub-agent notes"));
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
        fx.turn(pid, SubstrateV2Fixtures.traceId(), sessionId, base.plusMillis(2_500), null, assistant("Also this."));
        SpanRef scoredRef = seedTurn(pid, sessionId, base.plusMillis(3_000), user("nevermind"), null);

        assertTrue(new FrustrationTurnBuilder(assembler)
                .buildTurn(scored(pid, scoredRef))
                .isEmpty());
    }

    private SubstrateObservation scored(String pid, SpanRef ref) {
        return substrate
                .observationsByIds(pid, List.of(new GroundingEvidenceReads.SpanRef(ref.traceId(), ref.spanId())))
                .get(0);
    }

    private SpanRef seedTurn(
            String pid, String sessionId, Instant at, String input, @org.jspecify.annotations.Nullable String output) {
        return fx.turn(pid, SubstrateV2Fixtures.traceId(), sessionId, at, input, output);
    }

    /**
     * One turn of conversation {@code threadId} carrying agent+llm twins (and an optional tool span),
     * all spans of ONE trace so they share the turn. Returns the llm span — the scored subject.
     *
     * <p>The spans are written agent-then-llm-then-tool, in that order, because the thread window orders
     * by {@code (started_at, created_at, trace_id, id)}, the spans share a start, and {@code created_at}
     * is the row's own insert time.
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
                .kind("agent")
                .at(at)
                .payload(userInput, assistantOut)
                .write();
        SpanRef llm = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .threadId(threadId)
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
