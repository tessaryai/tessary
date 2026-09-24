// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                .observationById(pid, scoredRef.traceId(), scoredRef.spanId())
                .orElseThrow();
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
                .observationById(pid, scoredRef.traceId(), scoredRef.spanId())
                .orElseThrow();
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
                .observationById(pid, scoredRef.traceId(), scoredRef.spanId())
                .orElseThrow();
        StructuredThread thread = assembler.assembleStructured(scored).orElseThrow();

        assertEquals(
                List.of("can you export this?", "Sure.", "still broken", "Let me retry."),
                thread.earlier().stream().map(StructuredThread.Message::text).toList(),
                "turns stored after the scored one but started before it are earlier; the later turn is not");
        assertEquals("nevermind", thread.current().text());
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
