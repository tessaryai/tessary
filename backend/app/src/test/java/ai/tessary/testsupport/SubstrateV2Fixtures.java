// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SessionRow;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Row;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seeds v2 substrate rows — session, trace, span, payload — with producer ids, in the order the FKs
 * require.
 *
 * <p>The replacement for {@code TestContexts}, which seeds the v1 context spine. It lands before any
 * consumer migrates, deliberately: roughly fifty test files seed substrate rows, and letting each one
 * hand-write its own producer ids and FK ordering is how a schema migration turns into fifty small
 * incompatible dialects that all have to be re-fixed at the next change.
 *
 * <h2>What it protects you from</h2>
 *
 * <ul>
 *   <li><b>FK ordering.</b> {@code fk_trace_session} and {@code fk_span_trace} are real constraints, so a
 *       span cannot be seeded before its trace and a trace naming a session cannot be seeded before that
 *       session. {@link #span} and {@link #trace} get-or-create their ancestors, so a test that only cares
 *       about a span writes one line.
 *   <li><b>Producer-shaped ids.</b> {@link #traceId()} and {@link #spanId()} mint 32- and 16-character hex
 *       strings, the widths a W3C trace context actually carries. A test seeded with {@code "t1"} passes
 *       against code that would break on the real thing.
 *   <li><b>Honest empties.</b> The default span carries NO usage and NO cost, so it exercises the
 *       all-null-yields-null generated columns rather than quietly seeding zeros. Ask for usage
 *       explicitly.
 * </ul>
 *
 * <p>Rollup columns are never seeded. They are the rollup worker's output, and a test that wants them
 * populated should run the worker — a fixture that writes them directly is asserting against itself.
 */
public final class SubstrateV2Fixtures {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final SessionRepository sessions;
    private final TraceV2Repository traces;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final @Nullable JdbcClient jdbc;

    /** The v2 core tables only — enough for any test that never touches a side table. */
    public SubstrateV2Fixtures(
            SessionRepository sessions,
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads) {
        this(sessions, traces, spans, payloads, null);
    }

    /**
     * The v2 core tables plus {@code tool_call} / {@code retrieved_doc}.
     *
     * <p>Those two take a raw {@link JdbcClient} rather than a repository because they are not v2 tables:
     * their producer-key columns are additive prep (0077) that the v1 writer does not populate and no v2
     * repository owns yet. Wiring them through {@code ToolCallRepository} would mean teaching the v1
     * writer a v2 shape a release early, purely for tests.
     */
    public SubstrateV2Fixtures(
            SessionRepository sessions,
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            @Nullable JdbcClient jdbc) {
        this.sessions = sessions;
        this.traces = traces;
        this.spans = spans;
        this.payloads = payloads;
        this.jdbc = jdbc;
    }

    // ---- id minting -------------------------------------------------------------------------------

    /** A 16-hex-character OTel span id. */
    public static String spanId() {
        return hex(16);
    }

    /** A 32-hex-character OTel trace id. */
    public static String traceId() {
        return hex(32);
    }

    /** A producer session string — free-form by contract, so this one deliberately is not hex. */
    public static String sessionId() {
        return "sess-" + COUNTER.incrementAndGet();
    }

    private static String hex(int width) {
        String s = Long.toHexString(COUNTER.incrementAndGet() * 0x9E3779B97F4A7C15L);
        return (s + "0".repeat(width)).substring(0, width);
    }

    // ---- seeding ----------------------------------------------------------------------------------

    /** A session with both timestamps at {@code at}. */
    public SessionRow session(String projectId, String sessionId, Instant at) {
        SessionRow row = SessionRow.of(projectId, sessionId, null, at.toString(), at.toString());
        sessions.getOrCreate(row);
        return row;
    }

    /** A trace with no session, started at {@code at}. Rollup columns left null, as ingest leaves them. */
    public TraceV2Row trace(String projectId, String traceId, Instant startedAt) {
        return trace(projectId, traceId, null, startedAt);
    }

    /**
     * A trace, get-or-creating its session first when one is named — the §6.1 resolution order, which is
     * what makes {@code fk_trace_session} satisfiable.
     */
    public TraceV2Row trace(String projectId, String traceId, @Nullable String sessionId, Instant startedAt) {
        return trace(projectId, traceId, sessionId, null, null, startedAt);
    }

    /**
     * A trace naming both its session and its {@code thread_id} — the conversation grain every classifier
     * groups on, since {@code COALESCE(thread_id, session_id)} is the pinned conversation key.
     *
     * <p>Get-or-create, so the FIRST write of a trace id decides its correlation: a later call (including
     * the implicit one inside {@link #spanSeed}) will not move a session or thread that is already set.
     * Seed the trace before its spans whenever the correlation matters.
     */
    public TraceV2Row trace(
            String projectId,
            String traceId,
            @Nullable String sessionId,
            @Nullable String threadId,
            @Nullable String projectVersionId,
            Instant startedAt) {
        if (sessionId != null) {
            session(projectId, sessionId, startedAt);
        }
        TraceV2Row row = TraceV2Row.of(
                projectId,
                traceId,
                sessionId,
                threadId,
                null,
                null,
                projectVersionId,
                startedAt.toString(),
                startedAt.toString());
        traces.getOrCreate(row);
        return row;
    }

    /**
     * A NAMED trace with no spans — what a surface that renders trace-level facts only needs.
     *
     * <p>Separate from {@link #trace} because {@code name} is not correlation: a case page, a case
     * exemplar list and a deep link all render it, and none of them reads a span. Seeding a whole span
     * tree to get one label onto the row would be fixture theatre.
     */
    public TraceV2Row namedTrace(String projectId, String traceId, @Nullable String name, Instant at) {
        TraceV2Row row = TraceV2Row.of(projectId, traceId, null, null, name, null, null, at.toString(), at.toString());
        traces.getOrCreate(row);
        return row;
    }

    /**
     * A span, get-or-creating its trace first. No usage, no cost, {@code cost_source = 'unpriced'} — the
     * honest empty state. {@code eventTs} is the end when there is one, else the start, matching the
     * ingest mapper's rule.
     */
    public SpanRow span(
            String projectId,
            String traceId,
            String spanId,
            @Nullable String parentSpanId,
            String kind,
            Instant startedAt,
            @Nullable Instant endedAt) {
        trace(projectId, traceId, startedAt);
        SpanRow row = SpanRow.of(
                projectId,
                traceId,
                spanId,
                parentSpanId,
                kind,
                null,
                startedAt.toString(),
                endedAt == null ? null : endedAt.toString(),
                (endedAt == null ? startedAt : endedAt).toString());
        spans.upsert(row);
        return row;
    }

    /** A root LLM span of a fresh trace — the single commonest seed. Returns the row as written. */
    public SpanRow llmSpan(String projectId, String traceId, Instant startedAt) {
        return span(projectId, traceId, spanId(), null, "llm", startedAt, startedAt.plusMillis(250));
    }

    /**
     * Re-write a span with token usage attached, through the same LWW upsert production uses.
     *
     * <p>Takes {@link Long} rather than {@code long} on purpose: null and 0 are different facts here, and a
     * fixture that could not express "the producer sent no output tokens" would make the generated-column
     * invariant untestable.
     */
    public SpanRow withUsage(
            SpanRow row,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens) {
        SpanRow updated = new SpanRow(
                row.projectId(),
                row.traceId(),
                row.id(),
                row.parentSpanId(),
                row.path(),
                row.sessionId(),
                row.userId(),
                row.projectVersionId(),
                row.callSiteId(),
                row.traceName(),
                row.kind(),
                row.name(),
                row.isLogicalRoot(),
                row.status(),
                row.level(),
                row.errorType(),
                row.errorMessage(),
                row.startedAt(),
                row.endedAt(),
                row.latencyMs(),
                row.ttftMs(),
                row.providedModelName(),
                row.modelId(),
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
                reasoningTokens,
                row.inputCost(),
                row.outputCost(),
                row.cacheReadCost(),
                row.cacheWriteCost(),
                row.costSource(),
                row.priceBookVersion(),
                row.inputPreview(),
                row.outputPreview(),
                row.correlationState(),
                row.pathState(),
                row.eventTs(),
                row.isDeleted(),
                null,
                null,
                null,
                null);
        spans.upsert(updated);
        return updated;
    }

    /**
     * Re-write a span with per-bucket cost attached and the given {@code cost_source}.
     *
     * <p>Costs are plain decimal strings, bound as {@code numeric} — never {@code double}, which cannot
     * represent a rate exactly and is the wrong type for money at any scale.
     */
    public SpanRow withCost(
            SpanRow row,
            @Nullable String inputCost,
            @Nullable String outputCost,
            @Nullable String cacheReadCost,
            @Nullable String cacheWriteCost,
            String costSource) {
        SpanRow updated = new SpanRow(
                row.projectId(),
                row.traceId(),
                row.id(),
                row.parentSpanId(),
                row.path(),
                row.sessionId(),
                row.userId(),
                row.projectVersionId(),
                row.callSiteId(),
                row.traceName(),
                row.kind(),
                row.name(),
                row.isLogicalRoot(),
                row.status(),
                row.level(),
                row.errorType(),
                row.errorMessage(),
                row.startedAt(),
                row.endedAt(),
                row.latencyMs(),
                row.ttftMs(),
                row.providedModelName(),
                row.modelId(),
                row.inputTokens(),
                row.outputTokens(),
                row.cacheReadTokens(),
                row.cacheWriteTokens(),
                row.reasoningTokens(),
                inputCost,
                outputCost,
                cacheReadCost,
                cacheWriteCost,
                costSource,
                row.priceBookVersion(),
                row.inputPreview(),
                row.outputPreview(),
                row.correlationState(),
                row.pathState(),
                row.eventTs(),
                row.isDeleted(),
                null,
                null,
                null,
                null);
        spans.upsert(updated);
        return updated;
    }

    /**
     * Re-write a span with the previews and call site ingest cuts at write time.
     *
     * <p>These three are what the rollup copies down onto the trace from its ROOT span, so the traces list
     * stays a single-table read. A test for that copy has to be able to give the root something to copy.
     */
    public SpanRow withPreviews(
            SpanRow row, @Nullable String inputPreview, @Nullable String outputPreview, @Nullable String callSiteId) {
        SpanRow updated = new SpanRow(
                row.projectId(),
                row.traceId(),
                row.id(),
                row.parentSpanId(),
                row.path(),
                row.sessionId(),
                row.userId(),
                row.projectVersionId(),
                callSiteId,
                row.traceName(),
                row.kind(),
                row.name(),
                row.isLogicalRoot(),
                row.status(),
                row.level(),
                row.errorType(),
                row.errorMessage(),
                row.startedAt(),
                row.endedAt(),
                row.latencyMs(),
                row.ttftMs(),
                row.providedModelName(),
                row.modelId(),
                row.inputTokens(),
                row.outputTokens(),
                row.cacheReadTokens(),
                row.cacheWriteTokens(),
                row.reasoningTokens(),
                row.inputCost(),
                row.outputCost(),
                row.cacheReadCost(),
                row.cacheWriteCost(),
                row.costSource(),
                row.priceBookVersion(),
                inputPreview,
                outputPreview,
                row.correlationState(),
                row.pathState(),
                row.eventTs(),
                row.isDeleted(),
                null,
                null,
                null,
                null);
        spans.upsert(updated);
        return updated;
    }

    /**
     * Back-date a span's {@code created_at} — the INGEST clock, the one column {@link SpanRepository} will
     * not write.
     *
     * <p>It refuses on purpose: production stamps arrival, and letting a producer's payload move it would
     * let a producer move its own bill. Metering is the one reader that windows on it (billing is a
     * statement about when we accepted and stored data, not about when the agent ran), so a metering
     * fixture has to be able to say "this span was ingested inside that closed bucket" — and the only
     * honest way to say it is to write the column directly, in the fixture, rather than to soften the
     * repository.
     */
    public void ingestedAt(SpanRow span, Instant at) {
        jdbc().sql("""
                        UPDATE span SET created_at = :at::timestamptz
                        WHERE project_id = :pid AND trace_id = :tid AND id = :sid
                        """)
                .param("at", at.toString())
                .param("pid", span.projectId())
                .param("tid", span.traceId())
                .param("sid", span.id())
                .update();
    }

    /** The payload row for a span, written under the same {@code event_ts} the span carries. */
    public SpanPayloadRow payload(
            SpanRow span, @Nullable String input, @Nullable String output, @Nullable String attributesJson) {
        SpanPayloadRow row = new SpanPayloadRow(
                span.projectId(), span.traceId(), span.id(), input, output, attributesJson, null, span.eventTs());
        payloads.upsert(row);
        return row;
    }

    // ---- the fluent seed --------------------------------------------------------------------------

    /**
     * A span's producer identity. v2 has no bare-id address for a span — {@code (trace_id, span_id)} is
     * the handle every reader takes — so every seeder hands both halves back as one value rather than
     * letting a test carry a lone span id that cannot be resolved.
     */
    public record SpanRef(String traceId, String spanId) {}

    /**
     * Start a span seed: identity, correlation, typed usage/cost, and the payload text, written in FK
     * order when {@link SpanSeed#write()} is called.
     *
     * <p>The builder exists because a v2 span has thirty-odd meaningful columns and a fixture that took
     * them positionally would be unreadable at every call site and unextendable at all of them. Only what
     * a test actually asserts on gets named; everything else keeps the honest empty default (no usage, no
     * cost, {@code cost_source = 'unpriced'}).
     */
    public SpanSeed spanSeed(String projectId) {
        return new SpanSeed(this, projectId);
    }

    /**
     * The commonest seed of all: a whole turn as one trace with one root {@code llm} span carrying the
     * dialogue. Returns the span's producer identity, which is what the reader surfaces address.
     *
     * <p>{@code sessionId} may be null — an anonymous turn is its own single-turn conversation, which is
     * a real production state and a distinct code path in {@code conversationObservationsUpTo}.
     */
    public SpanRef turn(
            String projectId,
            String traceId,
            @Nullable String sessionId,
            Instant at,
            @Nullable String input,
            @Nullable String output) {
        return spanSeed(projectId)
                .traceId(traceId)
                .sessionId(sessionId)
                .at(at)
                .payload(input, output)
                .writeRef();
    }

    /**
     * Take a seeded trace all the way through the REAL rollup — the only supported way to get a settled
     * trace, with its counters, its {@code call_site_id} and its previews resolved from the root span.
     *
     * <p>Three production statements in production's order, no fixture arithmetic anywhere: the batch
     * timer fold ({@code applyBatchTimers}, which is what sets {@code has_root_span} and is the gate the
     * recompute's carry-down is behind), the claim (which is precisely "clear {@code rollup_due_at}"), and
     * the recompute. The batch is the trace's own spans, read back through the PK prefix and folded the
     * way {@code SpanBatchWriter} folds an arriving batch.
     *
     * <p>The claim is spelled directly rather than by calling {@code claimDue}, which claims only what is
     * already past its deadline and is global across projects: a fixture that used it would either sleep
     * out the two-second root deadline on every seeded trace or race the scheduled worker for the row.
     *
     * @return whether the trace settled — false only when it had vanished
     */
    public boolean rollup(String projectId, String traceId) {
        List<SpanRow> written = spans.listByTrace(projectId, traceId);
        if (!written.isEmpty()) {
            String minStarted = written.stream()
                    .map(SpanRow::startedAt)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            String maxEnded = written.stream()
                    .map(SpanRow::endedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            boolean hasRoot = written.stream().anyMatch(s -> s.parentSpanId() == null);
            traces.applyBatchTimers(
                    projectId, List.of(new TraceV2Repository.TimerUpdate(traceId, minStarted, maxEnded, hasRoot)));
        }
        jdbc().sql("UPDATE trace SET rollup_due_at = NULL WHERE project_id = :pid AND id = :tid")
                .param("pid", projectId)
                .param("tid", traceId)
                .update();
        return traces.recompute(projectId, traceId)
                .map(TraceV2Repository.Recomputed::settled)
                .orElse(false);
    }

    // ---- side tables ------------------------------------------------------------------------------

    /**
     * A {@code tool_call} row hung off a span by its PRODUCER keys — the join every v2 reader issues.
     *
     * <p>The v1 {@code observation_id} anchor is gone, as promised: nothing mints those ids any more, the
     * column is nullable, and ingest writes the producer keys and nothing else — so this fixture seeds
     * exactly what production writes.
     */
    public String toolCall(
            String projectId, SpanRef span, @Nullable String name, @Nullable String errorType, Instant at) {
        return toolCall(projectId, span, name, errorType, null, at);
    }

    /**
     * The same row, carrying the tool's RESULT payload.
     *
     * <p>Separate because the result is the half of the failure definition that has nothing to do with the
     * span's status: a framework that catches, hands {@code {"error": …}} back to the model and closes the
     * span cleanly writes a row with a null {@code error_type} and a failing result, and that shape is
     * unseedable without this parameter. Bound as {@code jsonb}, so a malformed literal fails here rather
     * than being stored as text the predicate can never match.
     */
    public String toolCall(
            String projectId,
            SpanRef span,
            @Nullable String name,
            @Nullable String errorType,
            @Nullable String resultJson,
            Instant at) {
        String id = "tc-" + COUNTER.incrementAndGet();
        jdbc().sql("""
                        INSERT INTO tool_call (id, project_id, name, error_type, is_error, result,
                                               trace_id, span_id, started_at, created_at, event_ts)
                        VALUES (:id, :pid, :name, :err, :isErr, CAST(:result AS jsonb),
                                :tid, :sid, :at::timestamptz, :at::timestamptz, :at::timestamptz)
                        """)
                .param("id", id)
                .param("pid", projectId)
                .param("name", name)
                .param("err", errorType)
                .param("isErr", errorType != null)
                .param("result", resultJson)
                .param("tid", span.traceId())
                .param("sid", span.spanId())
                .param("at", at.toString())
                .update();
        return id;
    }

    /**
     * A {@code retrieved_doc} row hung off a span by its producer keys. {@code listRole} is left null by
     * default on purpose — a producer that does not say is KEPT as evidence, and only an explicit
     * {@code 'candidate'} is dropped, so the two cases have to be seedable apart.
     */
    public String retrievedDoc(
            String projectId,
            SpanRef span,
            @Nullable String content,
            @Nullable Integer rank,
            @Nullable String listRole,
            Instant at) {
        String id = "rd-" + COUNTER.incrementAndGet();
        jdbc().sql("""
                        INSERT INTO retrieved_doc (id, project_id, content, rank, list_role,
                                                   trace_id, span_id, created_at, event_ts)
                        VALUES (:id, :pid, :content, :rank, :role,
                                :tid, :sid, :at::timestamptz, :at::timestamptz)
                        """)
                .param("id", id)
                .param("pid", projectId)
                .param("content", content)
                .param("rank", rank)
                .param("role", listRole)
                .param("tid", span.traceId())
                .param("sid", span.spanId())
                .param("at", at.toString())
                .update();
        return id;
    }

    private JdbcClient jdbc() {
        JdbcClient c = this.jdbc;
        if (c == null) {
            throw new IllegalStateException("side-table seeding needs the 5-arg SubstrateV2Fixtures constructor "
                    + "(the one taking a JdbcClient): tool_call and retrieved_doc are not v2 tables and have "
                    + "no v2 repository to write them through.");
        }
        return c;
    }

    /**
     * Fluent span seed — see {@link SubstrateV2Fixtures#spanSeed}.
     *
     * <p>Correlation set here is written ONTO THE SPAN and marked {@code done}, rather than left for the
     * {@code CorrelationBackfiller} to copy down from the trace. The backfiller is a {@code @Scheduled}
     * bean and ticks inside a {@code @SpringBootTest} context, so a fixture that depended on it would be
     * asserting against a race.
     */
    public static final class SpanSeed {

        private final SubstrateV2Fixtures fx;
        private final String projectId;
        private String traceId = SubstrateV2Fixtures.traceId();
        private String spanId = SubstrateV2Fixtures.spanId();
        private @Nullable String parentSpanId;
        private @Nullable String sessionId;
        private @Nullable String threadId;
        private @Nullable String userId;
        private @Nullable String projectVersionId;
        private @Nullable String callSiteId;
        private @Nullable String traceName;
        private String kind = "llm";
        private @Nullable String name = "chat";
        private @Nullable String status;
        private @Nullable String level;
        private @Nullable String errorType;
        private @Nullable String errorMessage;
        private Instant startedAt = Instant.now();
        private @Nullable Instant endedAt;
        private boolean unterminated;
        private @Nullable String providedModelName;
        private @Nullable String modelId;
        private @Nullable Long inputTokens;
        private @Nullable Long outputTokens;
        private @Nullable Long cacheReadTokens;
        private @Nullable Long cacheWriteTokens;
        private @Nullable Long reasoningTokens;
        private @Nullable String inputCost;
        private @Nullable String outputCost;
        private String costSource = SpanRow.CostSource.UNPRICED;
        private @Nullable String inputPreview;
        private @Nullable String outputPreview;
        private @Nullable String payloadInput;
        private @Nullable String payloadOutput;
        private @Nullable String payloadAttributes;
        private boolean writePayload;

        private SpanSeed(SubstrateV2Fixtures fx, String projectId) {
            this.fx = fx;
            this.projectId = projectId;
        }

        public SpanSeed traceId(String v) {
            this.traceId = v;
            return this;
        }

        public SpanSeed spanId(String v) {
            this.spanId = v;
            return this;
        }

        public SpanSeed parentSpanId(@Nullable String v) {
            this.parentSpanId = v;
            return this;
        }

        public SpanSeed sessionId(@Nullable String v) {
            this.sessionId = v;
            return this;
        }

        /** The producer's conversation id — takes precedence over the session as the grouping key. */
        public SpanSeed threadId(@Nullable String v) {
            this.threadId = v;
            return this;
        }

        public SpanSeed userId(@Nullable String v) {
            this.userId = v;
            return this;
        }

        public SpanSeed projectVersionId(@Nullable String v) {
            this.projectVersionId = v;
            return this;
        }

        public SpanSeed callSiteId(@Nullable String v) {
            this.callSiteId = v;
            return this;
        }

        public SpanSeed traceName(@Nullable String v) {
            this.traceName = v;
            return this;
        }

        public SpanSeed kind(String v) {
            this.kind = v;
            return this;
        }

        public SpanSeed name(@Nullable String v) {
            this.name = v;
            return this;
        }

        public SpanSeed status(@Nullable String v) {
            this.status = v;
            return this;
        }

        public SpanSeed level(@Nullable String v) {
            this.level = v;
            return this;
        }

        /** The error CLASS — a short label ("TimeoutError"), which is all this column ever holds. */
        public SpanSeed errorType(@Nullable String v) {
            this.errorType = v;
            return this;
        }

        /** The error PROSE. Its own column since 0001, so the class one stays a facet key (#762). */
        public SpanSeed errorMessage(@Nullable String v) {
            this.errorMessage = v;
            return this;
        }

        /** Start time; the end defaults to 250ms later unless {@link #endedAt} says otherwise. */
        public SpanSeed at(Instant v) {
            this.startedAt = v;
            return this;
        }

        public SpanSeed endedAt(@Nullable Instant v) {
            this.endedAt = v;
            return this;
        }

        /**
         * No end at all — {@code ended_at} stays NULL.
         *
         * <p>Distinct from leaving {@link #endedAt} unset, which takes the 250ms default: a span the
         * producer opened and never closed is a real and load-bearing state (an unterminated turn is a
         * counted category, not a row to drop), and it is unreachable through a builder whose "no value
         * given" and "no end exists" are the same call.
         */
        public SpanSeed unterminated() {
            this.unterminated = true;
            return this;
        }

        public SpanSeed model(@Nullable String providedModelName) {
            this.providedModelName = providedModelName;
            return this;
        }

        public SpanSeed modelId(@Nullable String v) {
            this.modelId = v;
            return this;
        }

        /** Token buckets. Null and 0 are different facts — null is what makes the totals read NULL. */
        public SpanSeed usage(@Nullable Long inputTokens, @Nullable Long outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            return this;
        }

        public SpanSeed cacheUsage(@Nullable Long cacheReadTokens, @Nullable Long cacheWriteTokens) {
            this.cacheReadTokens = cacheReadTokens;
            this.cacheWriteTokens = cacheWriteTokens;
            return this;
        }

        public SpanSeed reasoningTokens(@Nullable Long v) {
            this.reasoningTokens = v;
            return this;
        }

        /** Per-bucket cost as decimal strings, bound as {@code numeric} — never {@code double}. */
        public SpanSeed cost(@Nullable String inputCost, @Nullable String outputCost, String costSource) {
            this.inputCost = inputCost;
            this.outputCost = outputCost;
            this.costSource = costSource;
            return this;
        }

        public SpanSeed previews(@Nullable String inputPreview, @Nullable String outputPreview) {
            this.inputPreview = inputPreview;
            this.outputPreview = outputPreview;
            return this;
        }

        /** Payload text. Calling this at all is what makes a {@code span_payload} row exist. */
        public SpanSeed payload(@Nullable String input, @Nullable String output) {
            return payload(input, output, null);
        }

        public SpanSeed payload(@Nullable String input, @Nullable String output, @Nullable String attributesJson) {
            this.payloadInput = input;
            this.payloadOutput = output;
            this.payloadAttributes = attributesJson;
            this.writePayload = true;
            return this;
        }

        /** The identity this seed will write, available before {@link #write()} for wiring siblings. */
        public SpanRef ref() {
            return new SpanRef(traceId, spanId);
        }

        /** Write trace (get-or-create), span, and payload, in FK order. */
        public SpanRow write() {
            fx.trace(projectId, traceId, sessionId, threadId, projectVersionId, startedAt);
            Instant end = unterminated ? null : (endedAt == null ? startedAt.plusMillis(250) : endedAt);
            Instant eventTs = end == null ? startedAt : end;
            String correlationState = sessionId == null ? SpanRow.ResolverState.NONE : SpanRow.ResolverState.DONE;
            SpanRow row = new SpanRow(
                    projectId,
                    traceId,
                    spanId,
                    parentSpanId,
                    null,
                    sessionId,
                    userId,
                    projectVersionId,
                    callSiteId,
                    traceName,
                    kind,
                    name,
                    parentSpanId == null,
                    status,
                    level,
                    errorType,
                    errorMessage,
                    startedAt.toString(),
                    end == null ? null : end.toString(),
                    null,
                    null,
                    providedModelName,
                    modelId,
                    inputTokens,
                    outputTokens,
                    cacheReadTokens,
                    cacheWriteTokens,
                    reasoningTokens,
                    inputCost,
                    outputCost,
                    null,
                    null,
                    costSource,
                    null,
                    inputPreview,
                    outputPreview,
                    correlationState,
                    SpanRow.ResolverState.PENDING,
                    eventTs.toString(),
                    false,
                    null,
                    null,
                    null,
                    null);
            fx.spans.upsert(row);
            if (writePayload) {
                fx.payload(row, payloadInput, payloadOutput, payloadAttributes);
            }
            return row;
        }

        /** {@link #write()}, returning the producer identity rather than the row. */
        public SpanRef writeRef() {
            write();
            return ref();
        }
    }
}
