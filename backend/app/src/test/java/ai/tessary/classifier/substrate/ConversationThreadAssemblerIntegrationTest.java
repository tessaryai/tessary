// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierField;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DB-backed proof that {@link ConversationThreadAssembler} walks a whole conversation (not just the
 * scored row): three turns of one session, one carrying a system message, are assembled into the
 * contract-v1 dialogue thread — user↔assistant oldest-first, scored user message last, system message
 * excluded. Runs against the real pgvector Postgres (Testcontainers) so the conversation grouping +
 * keyset ordering execute for real.
 *
 * <p>The turn is the TRACE now, and the conversation is {@code COALESCE(trace.thread_id,
 * trace.session_id)} — a column on the row rather than a tier of a context tree. So the two things the
 * v1 shape of this test spent most of its lines on, minting a session→conversation→turn spine and
 * ordering by {@code context.seq}, have nothing to seed: a turn is one trace, and its position in the
 * thread is its keyset position.
 */
@SpringBootTest
class ConversationThreadAssemblerIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
        String rendered = assembler.assemble(scored, ClassifierField.INPUT);

        assertEquals(
                "[user] can you export this?\n"
                        + "[assistant] Sure, running the export now.\n"
                        + "[user] still broken\n"
                        + "[assistant] Let me retry.\n"
                        + "[user] nevermind",
                rendered);
        assertAll(
                () -> assertFalse(rendered.contains("export bot"), "system prompt excluded"),
                () -> assertFalse(rendered.contains("[system]"), "no system block emitted"),
                () -> assertTrue(rendered.endsWith("[user] nevermind"), "scored message last"));
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
        // turn 0 also has a tool span (surfaced as an outcome marker).
        seedTwinTurn(pid, sessionId, c1, base.plusMillis(1_000), user("deploy the app"), assistant("On it."), "search");
        seedTwinTurn(pid, sessionId, c1, base.plusMillis(2_000), user("still failing"), assistant("Retrying."), null);
        SpanRef scoredRef = seedTwinTurn(pid, sessionId, c1, base.plusMillis(3_000), user("nevermind"), null, null);

        SubstrateObservation scored = substrate
                .observationById(pid, scoredRef.traceId(), scoredRef.spanId())
                .orElseThrow();
        String rendered = assembler.assemble(scored, ClassifierField.INPUT);

        assertEquals(
                "[user] deploy the app\n"
                        + "[assistant] On it.\n"
                        + "[tool:search ok]\n"
                        + "[user] still failing\n"
                        + "[assistant] Retrying.\n"
                        + "[user] nevermind",
                rendered,
                "per-turn deltas concatenated in keyset order, each message once, tool outcome surfaced");
        assertAll(
                () -> assertFalse(rendered.contains("OTHER CONVERSATION"), "a sibling conversation never bleeds in"),
                () -> assertEquals(
                        1,
                        countOccurrences(rendered, "deploy the app"),
                        "the agent/llm twins collapse to one message per turn"),
                () -> assertTrue(
                        rendered.endsWith("[user] nevermind"), "the scored final user turn is one trailing message"));
    }

    /** One turn = one trace with a single root llm span carrying the dialogue. Returns the span. */
    private SpanRef seedTurn(
            String pid, String sessionId, Instant at, String input, @org.jspecify.annotations.Nullable String output) {
        return fx.turn(pid, SubstrateV2Fixtures.traceId(), sessionId, at, input, output);
    }

    /**
     * One turn of conversation {@code threadId} carrying agent+llm twins (and an optional tool span),
     * all spans of ONE trace so they share the turn. Returns the llm span — the scored subject.
     *
     * <p>The spans are written agent-then-llm-then-tool, in that order, because the thread window orders
     * by {@code (created_at, trace_id, id)} and {@code created_at} is the row's own insert time.
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    private static String user(String text) {
        return "[{\"role\":\"user\",\"content\":\"" + text + "\"}]";
    }

    private static String assistant(String text) {
        return "[{\"role\":\"assistant\",\"content\":\"" + text + "\"}]";
    }
}
