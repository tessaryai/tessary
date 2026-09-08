// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.ingest.GenAiAttributes;
import ai.tessary.evals.ingest.KindNormalizer;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.redaction.RedactionService;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance test for the async substrate write path end to end — {@code enqueue} → sample → redact →
 * drain → write — against the real pgvector Postgres (Testcontainers).
 *
 * <p><b>What this covers that {@code SpanBatchWriterIntegrationTest} does not.</b> That test calls the
 * writer directly to pin the §6/§7 protocol. This one goes through {@link SubstrateWriter}, so it is
 * where the things the drainer owns are asserted: that a replayed batch is a no-op end to end, that the
 * side tables the write path extracts land on the producer's own keys, and that a full burst drains
 * inside the throughput budget without ever blocking the producer.
 *
 * <p>No grading {@code run} row exists at any point and no LLM call happens — the path is structural
 * only.
 */
@SpringBootTest(properties = {"evals.ingest.substrate.rollup-enabled=false"})
class SubstrateWriteIntegrationTest {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SubstrateWriteIntegrationTest.class);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        // No queue override (#984, criterion 6): the burst below runs at the SHIPPED defaults. It is far
        // inside the byte budget (~1%), so it proves the defaults hold a real burst, not where the budget
        // sheds; SubstrateWriterResilienceTest covers the byte accounting at the edge.
    }

    @Autowired
    SubstrateWriter writer;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RedactionService redaction;

    /**
     * Counts JDBC executions (#984 M2, criterion 7): every {@code execute*} on a statement the pool hands
     * out is one, and a JDBC batch is one, because it is one round trip. Wraps the pool's DataSource in a
     * reflective proxy, so the count is what the driver was asked to run and not what a repository claims.
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

        // Only PreparedStatement executions are counted: the drain path issues no plain Statement, and a
        // commit is not a statement. Proxied with every interface the driver's object implements: the
        // pool casts the statement it is handed back to CallableStatement, and a PreparedStatement-only
        // proxy fails that cast.
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
                null,
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
                    null,
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

        // Structured, typed — not blobs: the kind and the correlation handles are queryable columns, and
        // the tool call is joined by the producer pair rather than a platform surrogate.
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
        // The structural user handle is lifted onto the session and denormalized onto the trace.
        assertEquals(
                2,
                jdbc.sql("SELECT COUNT(*) FROM session WHERE project_id = :pid AND user_id LIKE 'user-%'")
                        .param("pid", pid)
                        .query(Integer.class)
                        .single());

        // Replay the same batch (at-least-once delivery): every write dedupes on the producer's own keys.
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
        // v2 has no conversation tier: sessions never nest, and a producer's gen_ai.conversation.id is a
        // column on the trace. A conversation-only batch (no session.id) is legal and stays session-less
        // rather than having a session synthesized for it.
        String pid =
                TenantFixture.bootstrap(tenants, "substrate-conv").project().id();
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        List<RawEntry> batch = new ArrayList<>();
        for (int turn = 0; turn < 2; turn++) {
            batch.add(new RawEntry(
                    "sp-c1-" + turn,
                    null,
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
                null,
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
        // A trace-keyed source keys EVERY row of a trace by the trace id, so a multi-span trace arrives as
        // N entries sharing one span id. Span identity IS the producer's, so those are one span by
        // definition and the last-write-wins upsert resolves them — no duplicate rows, no failed batch.
        // (What is lost is telemetry the producer made indistinguishable; that is the producer's bug, and
        // it is now visible as a span count rather than hidden behind a synthesized surrogate.)
        String pid =
                TenantFixture.bootstrap(tenants, "substrate-upload").project().id();
        Map<String, Object> meta = Map.of("session.id", "conv-up");
        String t0 = Instant.parse("2026-01-01T00:00:00Z").toString();
        List<RawEntry> batch = List.of(
                new RawEntry(
                        "tr-up",
                        null,
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
                        null,
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
                        null,
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
        // An OpenInference RETRIEVER span flattens its retrieved passages as
        // retrieval.documents.{N}.document.{id|content|score} attributes; the write path re-assembles them
        // into retrieved_doc rows keyed on the producer pair, and the span end-time lands as ended_at.
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
                null,
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

        // Replay (at-least-once): each document's id is derived from (project, trace, span, seq).
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

        // A tool span: name/id/type live in gen_ai.tool.* attributes; args ride the typed messages.
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
                null,
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
        // A chat span carrying token usage including cache tokens.
        Map<String, Object> usageMeta = new java.util.HashMap<>();
        usageMeta.put("session.id", "conv-tool");
        usageMeta.put(GenAiAttributes.USAGE_INPUT_TOKENS, 40053);
        usageMeta.put(GenAiAttributes.USAGE_OUTPUT_TOKENS, 5);
        usageMeta.put(GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS, 15206);
        usageMeta.put(GenAiAttributes.USAGE_CACHE_CREATION_INPUT_TOKENS, 16829);
        RawEntry chat = new RawEntry(
                "sp-chat-u", null, "chat gpt", "i", "o", "gpt-x", usageMeta, null, "tr-tool", t0, KindNormalizer.LLM);

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

        // Usage is typed columns now, not a jsonb bag a reader has to parse.
        assertEquals(
                "15206", one("SELECT cache_read_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid));
        assertEquals(
                "16829",
                one("SELECT cache_write_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid));
        // The cache-inclusive correction happens at WRITE time now, for every producer: the OTel
        // convention defines gen_ai.usage.input_tokens as the whole prompt, so BOTH cache buckets
        // (15,206 read + 16,829 write) are carved out of the stated 40,053 and input_tokens stores the
        // 8,018 fresh ones. Left in, those tokens would be billed twice — once at the input rate, once at
        // their own cache rate — and, because cost is never repriced, permanently.
        assertEquals("8018", one("SELECT input_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid));
        assertEquals(
                "40058",
                one("SELECT total_tokens::text FROM span WHERE project_id = :pid AND kind = 'llm'", pid),
                "total_tokens is generated: the disjoint buckets, summed by the database");
    }

    /**
     * Criterion 9 (#984): the same burst drained twice, once on a project with no rules (the guard's
     * no-op path) and once with every built-in rule enabled, over bodies large enough for the regexes
     * to matter. The ratio is logged for the runbook's "Redaction is burning CPU" section; the bound
     * is only there so a regression back to a quadratic pattern (the 2026-07-31 incident, 8 KB = 172 ms)
     * fails loudly rather than being read off a log line.
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

    /** Five hundred root spans with the given body, teed one conversation per batch; returns the drain time. */
    private long drainBurst(String pid, String body, String tag) throws InterruptedException {
        List<List<RawEntry>> batches = new ArrayList<>();
        for (int c = 0; c < 100; c++)
            batches.add(chattyConversation("chat-" + tag + "-" + c, "tr-" + tag + "-" + c, body));
        long start = System.nanoTime();
        for (List<RawEntry> b : batches) assertTrue(writer.enqueue(pid, b));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(120)), "burst " + tag + " drained");
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    /** About 4 KB of prose with one address and one phone number, the shape of a real support transcript. */
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
                    null,
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

        // A full-sized ingest burst: 1000 spans across 20
        // sessions x 5 conversations, teed batch-per-conversation like the dataset path.
        List<List<RawEntry>> batches = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            for (int c = 0; c < 5; c++) {
                batches.add(conversation("burst-" + s, "tr-" + s + "-" + c, 9));
            }
        }
        long shedBefore = writer.shedBatches();

        long enqueueStart = System.nanoTime();
        // The drain clock starts with the first enqueue: the drainer is already writing while the burst
        // is still being offered, so a clock started after the loop would under-measure the objective.
        long drainStart = enqueueStart;
        for (List<RawEntry> b : batches) {
            writer.enqueue(pid, b);
        }
        long enqueueMillis = Duration.ofNanos(System.nanoTime() - enqueueStart).toMillis();
        // Producer-side SLO: the tee is non-blocking — enqueueing the whole burst is instant
        // relative to any I/O (generous CI bound; locally this is < 5ms).
        assertTrue(enqueueMillis < 1000, "enqueue of 100 batches took " + enqueueMillis + "ms");

        // Drainer-side SLO: >= 200 spans/s sustained => 1000 spans in <= 5s; allow generous CI
        // headroom but fail if throughput collapses.
        long executionsBefore = StatementCounting.EXECUTIONS.get();
        assertTrue(writer.awaitIdle(Duration.ofSeconds(120)), "burst drained");
        long drainMillis = Duration.ofNanos(System.nanoTime() - drainStart).toMillis();
        long executions = StatementCounting.EXECUTIONS.get() - executionsBefore;

        assertEquals(0L, writer.shedBatches() - shedBefore, "shipped defaults hold the whole burst — nothing shed");
        // The published drain objective (#984, criterion 5): >= 200 spans/s sustained on the reference
        // Postgres, so 1,000 spans in at most 5 s. Measured 0.5 s on a laptop after M2, so this is a 10x
        // headroom for CI, and a collapse to per-span round trips (twice that) still fails it.
        assertTrue(
                drainMillis <= 5_000, "1000 spans drained in " + drainMillis + " ms, wanted <= 5000 (>= 200 spans/s)");
        assertEquals(0L, writer.failedBatches());
        assertEquals(1000, count("span", pid), "every span of the burst landed exactly once");
        // Surface the measured rate for the SLO statement (>= 200 spans/s target, CI headroom above).
        log.info("substrate burst: 1000 spans drained in {} ms, {} JDBC executions", drainMillis, executions);
        // O(1) statements per batch, not O(spans) (#984 M2): one JDBC batch per table plus the batch-scoped
        // reads and updates. 100 batches of 10 spans used to cost well over 2,000 executions.
        assertTrue(
                executions <= 100 * 15, "JDBC executions for 100 batches: " + executions + ", wanted O(1) per batch");

        // The same constant must hold for a batch twenty times larger, or the bound above only proves
        // "about one statement per span": one 200-span conversation costs the same handful of executions.
        long largeBefore = StatementCounting.EXECUTIONS.get();
        writer.enqueue(pid, conversation("burst-large", "tr-large", 199));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(60)), "large batch drained");
        long largeExecutions = StatementCounting.EXECUTIONS.get() - largeBefore;
        assertEquals(1200, count("span", pid), "the large batch landed too");
        assertTrue(largeExecutions <= 15, "JDBC executions for one 200-span batch: " + largeExecutions);
    }
}
