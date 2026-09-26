// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.redaction.RedactionService;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The async substrate write path end to end (enqueue, sample, redact, drain, write) against real Postgres, through
 * {@link SubstrateWriter} rather than the writer directly: a replayed batch is a no-op, side tables land on producer
 * keys, and a full burst drains inside the budget without blocking the producer.
 *
 * <p>The burst runs at the shipped queue defaults, about 1% of the byte budget; {@code SubstrateWriterResilienceTest}
 * covers the edge.
 */
@SpringBootTest(properties = {"tessary.ingest.substrate.rollup-enabled=false"})
// Own context: it counts JDBC round trips across a burst, which another class's writes would corrupt.
@TestPropertySource(properties = "test.context-group=substrate-write")
class SubstrateWriteIntegrationTest {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SubstrateWriteIntegrationTest.class);

    @Autowired
    SubstrateWriter writer;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RedactionService redaction;

    /**
     * Counts JDBC executions (a batch is one round trip) through a proxied DataSource, so the count is what the
     * driver ran.
     */
    @TestConfiguration
    static class StatementCounting {
        static final AtomicLong EXECUTIONS = new AtomicLong();

        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource ds)) return bean;
                    return Proxy.newProxyInstance(
                            ds.getClass().getClassLoader(), interfacesOf(ds), (proxy, m, args) -> {
                                Object out = invoke(m, ds, args);
                                return out instanceof Connection c ? countingConnection(c) : out;
                            });
                }
            };
        }

        static Connection countingConnection(Connection c) {
            return (Connection) Proxy.newProxyInstance(
                    c.getClass().getClassLoader(), interfacesOf(c), (proxy, m, args) -> {
                        Object out = invoke(m, c, args);
                        return out instanceof PreparedStatement ps ? countingStatement(ps) : out;
                    });
        }

        // Only PreparedStatement executions count. Proxied with every interface the driver's object has, because the
        // pool casts it back to CallableStatement.
        static PreparedStatement countingStatement(PreparedStatement ps) {
            return (PreparedStatement)
                    Proxy.newProxyInstance(ps.getClass().getClassLoader(), interfacesOf(ps), (proxy, m, args) -> {
                        if (m.getName().startsWith("execute")) EXECUTIONS.incrementAndGet();
                        return invoke(m, ps, args);
                    });
        }

        static Object invoke(Method m, Object target, Object[] args) throws Throwable {
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        static Class<?>[] interfacesOf(Object o) {
            Set<Class<?>> out = new LinkedHashSet<>();
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                for (Class<?> i : c.getInterfaces()) collect(i, out);
            }
            return out.toArray(Class<?>[]::new);
        }

        static void collect(Class<?> i, Set<Class<?>> out) {
            if (!Modifier.isPublic(i.getModifiers())) return;
            if (out.add(i)) {
                for (Class<?> parent : i.getInterfaces()) collect(parent, out);
            }
        }
    }

    private int count(String table, String projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id = :pid")
                .param("pid", projectId)
                .query(Integer.class)
                .single();
    }

    private @org.jspecify.annotations.Nullable String one(String sql, String projectId) {
        return jdbc.sql(sql).param("pid", projectId).query(String.class).single();
    }

    private static List<RawEntry> conversation(String sessionKey, String traceId, int toolSpans) {
        List<RawEntry> spans = new ArrayList<>();
        Map<String, Object> meta = Map.of("session.id", sessionKey, "user.id", "user-" + sessionKey);
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        spans.add(new RawEntry(
                traceId + "-root",
                "agent",
                "user question",
                "agent answer",
                null,
                meta,
                null,
                traceId,
                t0,
                KindNormalizer.AGENT));
        for (int i = 0; i < toolSpans; i++) {
            spans.add(new RawEntry(
                    traceId + "-tool-" + i,
                    "search",
                    "{\"q\":\"" + i + "\"}",
                    "{\"hits\":" + i + "}",
                    null,
                    meta,
                    traceId + "-root",
                    traceId,
                    t0,
                    KindNormalizer.TOOL));
        }
        return spans;
    }

    @Test
    void writesTheAgentNativeShape_andReplayIsIdempotent() throws InterruptedException {
        String pid =
                TenantFixture.bootstrap(tenants, "substrate-write").project().id();
        List<RawEntry> batch = new ArrayList<>();
        batch.addAll(conversation("conv-1", "tr-1", 2));
        batch.addAll(conversation("conv-1", "tr-2", 1));
        batch.addAll(conversation("conv-2", "tr-3", 0));

        writer.enqueue(pid, batch);
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        assertEquals(2, count("session", pid), "one session per provider session key");
        assertEquals(3, count("trace", pid), "one trace per provider trace id");
        assertEquals(6, count("span", pid), "one typed span per producer span");
        assertEquals(6, count("span_payload", pid), "and one payload row beside it");
        assertEquals(3, count("tool_call", pid), "every tool span lands as a first-class tool_call");

        // Typed, queryable columns, and tool calls joined by the producer pair.
        assertEquals(
                3,
                jdbc.sql("SELECT COUNT(*) FROM span WHERE project_id = :pid AND kind = 'tool'")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single());
        assertEquals(
                3,
                jdbc.sql("SELECT COUNT(*) FROM tool_call tc JOIN span s"
                                + "   ON s.project_id = tc.project_id"
                                + "  AND s.trace_id = tc.trace_id"
                                + "  AND s.id = tc.span_id"
                                + " WHERE tc.project_id = :pid")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single(),
                "every tool_call resolves back to its span by the producer pair");
        assertEquals(
                0,
                jdbc.sql("SELECT COUNT(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'public' AND table_name = 'tool_call'"
                                + "   AND column_name = 'observation_id'")
                        .query(Integer.class)
                        .single(),
                "and there is no v1 surrogate pointer left to carry — 0083 dropped the column with the"
                        + " table it pointed at");
        // The user handle is lifted onto the session and denormalized onto the trace.
        assertEquals(
                2,
                jdbc.sql("SELECT COUNT(*) FROM session WHERE project_id = :pid AND user_id LIKE 'user-%'")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single());

        // A replayed batch dedupes on the producer's keys.
        writer.enqueue(pid, batch);
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained replay");
        assertEquals(2, count("session", pid));
        assertEquals(3, count("trace", pid));
        assertEquals(6, count("span", pid));
        assertEquals(6, count("span_payload", pid));
        assertEquals(3, count("tool_call", pid), "a replayed tool call collides on its derived primary key");
        assertEquals(0L, writer.failedBatches(), "no batch exhausted its retries");
    }

    @Test
    void conversationIdRidesTheTraceRatherThanASecondTreeLevel() throws InterruptedException {
        // v2 has no conversation tier: a conversation-only batch stays session-less rather than getting a synthesized
        // session.
        String pid =
                TenantFixture.bootstrap(tenants, "substrate-conv").project().id();
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        List<RawEntry> batch = new ArrayList<>();
        for (int turn = 0; turn < 2; turn++) {
            batch.add(new RawEntry(
                    "sp-c1-" + turn,
                    "chat",
                    "q",
                    "a",
                    null,
                    Map.of("session.id", "s-9", GenAiAttributes.CONVERSATION_ID, "conv-1", "user.id", "user-9"),
                    null,
                    "tr-c1-" + turn,
                    t0,
                    KindNormalizer.LLM));
        }
        batch.add(new RawEntry(
                "sp-solo",
                "chat",
                "q",
                "a",
                null,
                Map.of(GenAiAttributes.CONVERSATION_ID, "conv-solo", "user.id", "user-7"),
                null,
                "tr-solo",
                t0,
                KindNormalizer.LLM));

        writer.enqueue(pid, batch);
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        assertEquals(1, count("session", pid), "one session row for s-9, and none synthesized for the solo trace");
        assertEquals(3, count("trace", pid));
        assertEquals(
                2,
                jdbc.sql("SELECT COUNT(*) FROM trace WHERE project_id = :pid"
                                + " AND session_id = 's-9' AND thread_id = 'conv-1'")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single(),
                "both session traces carry the conversation as a column");
        assertEquals(
                1,
                jdbc.sql("SELECT COUNT(*) FROM trace WHERE project_id = :pid"
                                + " AND session_id IS NULL AND thread_id = 'conv-solo'")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single(),
                "and the session-less trace keeps its conversation without inventing a session");

        writer.enqueue(pid, batch);
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained replay");
        assertEquals(1, count("session", pid));
        assertEquals(3, count("trace", pid));
        assertEquals(0L, writer.failedBatches(), "no batch exhausted its retries");
    }

    @Test
    void aTraceKeyedSourceThatReusesOneSpanIdCollapsesToOneSpan() throws InterruptedException {
        // A trace-keyed source gives every row of a trace one span id; span identity is the producer's, so the LWW
        // upsert makes them one span, with no duplicates and no failed batch.
        String pid =
                TenantFixture.bootstrap(tenants, "substrate-upload").project().id();
        Map<String, Object> meta = Map.of("session.id", "conv-up");
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        List<RawEntry> batch = List.of(
                new RawEntry(
                        "tr-up",
                        "agent",
                        "user question",
                        "agent answer",
                        null,
                        meta,
                        null,
                        "tr-up",
                        t0,
                        KindNormalizer.AGENT),
                new RawEntry(
                        "tr-up",
                        "search",
                        "{\"q\":\"a\"}",
                        "{\"hits\":1}",
                        null,
                        meta,
                        null,
                        "tr-up",
                        t0,
                        KindNormalizer.TOOL),
                new RawEntry(
                        "tr-up",
                        "search",
                        "{\"q\":\"b\"}",
                        "{\"hits\":2}",
                        null,
                        meta,
                        null,
                        "tr-up",
                        t0,
                        KindNormalizer.TOOL));

        long failedBefore = writer.failedBatches();
        writer.enqueue(pid, batch);
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "upload-shape batch drained");

        assertEquals(0L, writer.failedBatches() - failedBefore, "the batch committed once, whole");
        assertEquals(1, count("session", pid));
        assertEquals(1, count("trace", pid));
        assertEquals(1, count("span", pid), "one producer span id is one span");
        assertEquals(1, count("tool_call", pid), "and one tool call, keyed on that same pair");
    }

    @Test
    void aRetrievalSpanLandsItsPassagesAsFirstClassRows() throws InterruptedException {
        // OpenInference RETRIEVER passages become retrieved_doc rows keyed on the producer pair; the span's end lands
        // as ended_at.
        String pid = TenantFixture.bootstrap(tenants, "substrate-rag").project().id();
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        String t1 = Instant.parse("2026-01-01T00:00:01Z").toString();
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("session.id", "conv-rag");
        meta.put("retrieval.documents.0.document.id", "docA");
        meta.put("retrieval.documents.0.document.content", "passage A");
        meta.put("retrieval.documents.0.document.score", 0.91);
        meta.put("retrieval.documents.1.document.content", "passage B");
        meta.put("retrieval.documents.1.document.score", 0.42);
        RawEntry retriever = new RawEntry(
                "sp-rag",
                "search docs",
                "what is X?",
                null,
                null,
                meta,
                null,
                "tr-rag",
                t0,
                KindNormalizer.RETRIEVAL,
                t1,
                null,
                null);

        writer.enqueue(pid, List.of(retriever));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "retriever batch drained");

        assertEquals(1, count("span", pid));
        assertEquals(2, count("retrieved_doc", pid), "each retrieved passage lands as a first-class row");
        assertEquals(
                1,
                jdbc.sql("SELECT COUNT(*) FROM span WHERE project_id = :pid"
                                + " AND ended_at IS NOT NULL AND latency_ms = 1000")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single(),
                "the span end-time lands as ended_at, and the duration is a generated column");
        assertEquals(
                2,
                jdbc.sql("SELECT COUNT(*) FROM retrieved_doc WHERE project_id = :pid"
                                + " AND trace_id = 'tr-rag' AND span_id = 'sp-rag'")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single(),
                "both rows carry the producer pair");
        double topScore = jdbc.sql("SELECT score FROM retrieved_doc WHERE project_id = :pid ORDER BY seq ASC LIMIT 1")
                .param("pid", pid)
                .query(Double.class)
                .single();
        assertEquals(0.91, topScore, 1e-9, "the top-ranked document keeps its score, ordered by seq");

        // Each document id derives from (project, trace, span, seq).
        writer.enqueue(pid, List.of(retriever));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "replay drained");
        assertEquals(2, count("retrieved_doc", pid), "replay collides on the derived primary key");
        assertEquals(0L, writer.failedBatches(), "no batch exhausted its retries");
    }

    @Test
    void toolCallReadsTheStandardToolAttributes_andUsageIsTypedIntoColumns() throws InterruptedException {
        String pid =
                TenantFixture.bootstrap(tenants, "toolcall-usage").project().id();
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();

        // Tool name, id and type come from gen_ai.tool.* attributes.
        Map<String, Object> toolMeta = Map.of(
                "session.id",
                "conv-tool",
                GenAiAttributes.TOOL_NAME,
                "Bash",
                GenAiAttributes.TOOL_CALL_ID,
                "tu_1",
                GenAiAttributes.TOOL_TYPE,
                "function");
        RawEntry tool = new RawEntry(
                "sp-tool",
                "execute_tool Bash",
                null,
                null,
                null,
                toolMeta,
                null,
                "tr-tool",
                t0,
                KindNormalizer.TOOL,
                null,
                "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"id\":\"tu_1\",\"name\":\"Bash\",\"arguments\":{\"command\":\"ls\"}}]}]",
                "[{\"role\":\"tool\",\"parts\":[{\"type\":\"tool_result\",\"id\":\"tu_1\",\"content\":\"file.txt\"}]}]");
        Map<String, Object> usageMeta = new java.util.HashMap<>();
        usageMeta.put("session.id", "conv-tool");
        usageMeta.put(GenAiAttributes.USAGE_INPUT_TOKENS, 40053);
        usageMeta.put(GenAiAttributes.USAGE_OUTPUT_TOKENS, 5);
        usageMeta.put(GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS, 15206);
        usageMeta.put(GenAiAttributes.USAGE_CACHE_CREATION_INPUT_TOKENS, 16829);
        RawEntry chat = new RawEntry(
                "sp-chat-u", "chat gpt", "i", "o", "gpt-x", usageMeta, null, "tr-tool", t0, KindNormalizer.LLM);

        writer.enqueue(pid, List.of(chat, tool));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "batch drained");

        assertEquals(
                "Bash",
                one("SELECT name FROM tool_call WHERE project_id = :pid", pid),
                "tool_call.name from gen_ai.tool.name, not the span name");
        assertEquals(
                "tu_1",
                one("SELECT tool_call_id FROM tool_call WHERE project_id = :pid", pid),
                "tool_call_id from gen_ai.tool.call.id");
        assertEquals(
                "function",
                one("SELECT tool_type FROM tool_call WHERE project_id = :pid", pid),
                "tool_type from gen_ai.tool.type");
        String args = one("SELECT arguments::text FROM tool_call WHERE project_id = :pid", pid);
        assertNotNull(args);
        assertTrue(args.contains("command"), "clean arguments = the tool_call part's arguments, not the wrapper");

        assertEquals(
                "15206", one("SELECT cache_read_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid));
        assertEquals(
                "16829",
                one("SELECT cache_write_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid));
        // The OTel convention makes input_tokens the whole prompt, so both cache buckets are carved out at write
        // time, leaving the 8,018 fresh tokens. Left in, they would be billed twice, permanently, since cost is never
        // repriced.
        assertEquals("8018", one("SELECT input_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid));
        assertEquals(
                "40058",
                one("SELECT total_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid),
                "total_tokens is generated: the disjoint buckets, summed by the database");
    }

    /**
     * The same burst drained with no rules and with every built-in rule, over bodies big enough for the regexes to
     * matter. The ratio is logged for the runbook; the bound fails a regression to a quadratic pattern (the
     * 2026-07-31 incident: 8 KB in 172 ms).
     */
    @Test
    void redactionCostIsMeasuredAgainstTheUnredactedDrain() throws InterruptedException {
        String plain = TenantFixture.bootstrap(tenants, "substrate-redact-off")
                .project()
                .id();
        String guarded = TenantFixture.bootstrap(tenants, "substrate-redact-on")
                .project()
                .id();
        assertFalse(redaction.listRules(guarded).isEmpty(), "listing seeds the built-in rules, enabled");

        String body = chattyBody();
        long plainMillis = Math.min(drainBurst(plain, body, "off-a"), drainBurst(plain, body, "off-b"));
        long guardedMillis = Math.min(drainBurst(guarded, body, "on-a"), drainBurst(guarded, body, "on-b"));
        assertEquals(0L, writer.failedBatches());
        assertEquals(1000, count("span", plain));
        assertEquals(1000, count("span", guarded));
        int redacted = jdbc.sql(
                        "SELECT count(*) FROM span_payload WHERE project_id = :pid AND input LIKE '%[REDACTED_EMAIL]%'")
                .param("pid", guarded)
                .query(Integer.class)
                .single();
        assertEquals(1000, redacted, "every span body of both guarded runs was redacted");
        double ratio = (double) guardedMillis / Math.max(1, plainMillis);
        log.info(
                "redaction cost: 500 spans x {} B bodies drained in {} ms without rules, {} ms with all built-ins, ratio {}",
                body.length(),
                plainMillis,
                guardedMillis,
                String.format(Locale.ROOT, "%.2f", ratio));
        assertTrue(ratio < 5.0, "redaction multiplied drain time by " + ratio + ", wanted < 5x");
    }

    /** 500 root spans with the given body, one conversation per batch; returns the drain time. */
    private long drainBurst(String pid, String body, String tag) throws InterruptedException {
        List<List<RawEntry>> batches = new ArrayList<>();
        for (int c = 0; c < 100; c++)
            batches.add(chattyConversation("chat-" + tag + "-" + c, "tr-" + tag + "-" + c, body));
        long start = System.nanoTime();
        for (List<RawEntry> b : batches) assertTrue(writer.enqueue(pid, b));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(120)), "burst " + tag + " drained");
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    /** About 4 KB of support-transcript prose with one address and one phone number. */
    private static String chattyBody() {
        StringBuilder sb = new StringBuilder(4200);
        while (sb.length() < 4000) {
            sb.append(
                    "The customer asked whether the order placed last Tuesday could still be changed before it ships, ");
            sb.append("and the agent looked up the shipment status and explained the cutoff for changes. ");
        }
        sb.append(" Reach me at jordan.rivera@example.com or +1 415-555-0142 if anything changes.");
        return sb.toString();
    }

    private static List<RawEntry> chattyConversation(String sessionKey, String traceId, String body) {
        List<RawEntry> spans = new ArrayList<>();
        Map<String, Object> meta = Map.of("session.id", sessionKey, "user.id", "user-" + sessionKey);
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        for (int i = 0; i < 5; i++) {
            spans.add(new RawEntry(
                    traceId + "-root-" + i,
                    "agent",
                    body,
                    "agent answer " + i,
                    null,
                    meta,
                    null,
                    traceId + "-" + i,
                    t0,
                    KindNormalizer.AGENT));
        }
        return spans;
    }

    @Test
    void burstDrainsWithinBudget_andNeverBlocksTheProducer() throws InterruptedException {
        String pid =
                TenantFixture.bootstrap(tenants, "substrate-burst").project().id();

        // 1,000 spans over 20 sessions x 5 conversations, one batch per conversation.
        List<List<RawEntry>> batches = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            for (int c = 0; c < 5; c++) {
                batches.add(conversation("burst-" + s, "tr-" + s + "-" + c, 9));
            }
        }
        long shedBefore = writer.shedBatches();

        long enqueueStart = System.nanoTime();
        // The drain clock starts at the first enqueue, since the drainer writes while the burst is offered.
        long drainStart = enqueueStart;
        for (List<RawEntry> b : batches) {
            writer.enqueue(pid, b);
        }
        long enqueueMillis = Duration.ofNanos(System.nanoTime() - enqueueStart).toMillis();
        // The tee is non-blocking: enqueueing the burst is instant beside any I/O.
        assertTrue(enqueueMillis < 1000, "enqueue of 100 batches took " + enqueueMillis + "ms");

        long executionsBefore = StatementCounting.EXECUTIONS.get();
        assertTrue(writer.awaitIdle(Duration.ofSeconds(120)), "burst drained");
        long drainMillis = Duration.ofNanos(System.nanoTime() - drainStart).toMillis();
        long executions = StatementCounting.EXECUTIONS.get() - executionsBefore;

        assertEquals(0L, writer.shedBatches() - shedBefore, "shipped defaults hold the whole burst — nothing shed");
        // The published objective is at least 200 spans/s, so 1,000 spans within 5 s. About 0.5 s locally: 10x CI
        // headroom, and per-span round trips still fail it.
        assertTrue(
                drainMillis <= 5_000, "1000 spans drained in " + drainMillis + " ms, wanted <= 5000 (>= 200 spans/s)");
        assertEquals(0L, writer.failedBatches());
        assertEquals(1000, count("span", pid), "every span of the burst landed exactly once");
        log.info("substrate burst: 1000 spans drained in {} ms, {} JDBC executions", drainMillis, executions);
        // Constant statements per batch, not per span; 100 batches of 10 once cost over 2,000 executions.
        assertTrue(
                executions <= 100 * 15, "JDBC executions for 100 batches: " + executions + ", wanted O(1) per batch");

        // A batch twenty times larger costs the same handful, or the bound only proves "about one per span".
        long largeBefore = StatementCounting.EXECUTIONS.get();
        writer.enqueue(pid, conversation("burst-large", "tr-large", 199));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(60)), "large batch drained");
        long largeExecutions = StatementCounting.EXECUTIONS.get() - largeBefore;
        assertEquals(1200, count("span", pid), "the large batch landed too");
        assertTrue(largeExecutions <= 15, "JDBC executions for one 200-span batch: " + largeExecutions);
    }
}
