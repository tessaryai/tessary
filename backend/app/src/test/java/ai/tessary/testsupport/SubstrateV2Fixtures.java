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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seeds v2 substrate rows (session, trace, span, payload) with producer-shaped ids, in FK order.
 *
 * <p>{@link #span} and {@link #trace} get-or-create their ancestors, so {@code fk_trace_session} and {@code
 * fk_span_trace} hold. Ids have the W3C widths (32 and 16 hex), because a test seeded with {@code "t1"} passes
 * against code that breaks on real ids. The default span carries no usage and no cost, so the all-null generated
 * columns are exercised; ask for usage explicitly.
 *
 * <p>Rollup columns are never seeded: run the worker, or the test asserts against itself.
 */
public final class SubstrateV2Fixtures {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final HexFormat HEX = HexFormat.of();

    private final SessionRepository sessions;
    private final TraceV2Repository traces;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final @Nullable JdbcClient jdbc;

    public SubstrateV2Fixtures(
            SessionRepository sessions,
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads) {
        this(sessions, traces, spans, payloads, null);
    }

    /**
     * The core tables plus {@code tool_call} and {@code retrieved_doc}, which take a raw {@link JdbcClient}: their
     * producer-key columns (0077) have no v2 repository yet.
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
        return HEX.toHexDigits(nextMixed());
    }

    /** A 32-hex-character OTel trace id. Unique by its first half; the second half only adds variety. */
    public static String traceId() {
        long mixed = nextMixed();
        return HEX.toHexDigits(mixed) + HEX.toHexDigits(Long.reverse(mixed) * 0xBF58476D1CE4E5B9L);
    }

    /** A producer session string — free-form by contract, so this one deliberately is not hex. */
    public static String sessionId() {
        return "sess-" + COUNTER.incrementAndGet();
    }

    /**
     * An odd multiplier is a bijection on {@code long}, so each counter value mixes to a distinct value; the old
     * right-pad made {@code c} and {@code 16c} collide.
     */
    private static long nextMixed() {
        return COUNTER.incrementAndGet() * 0x9E3779B97F4A7C15L;
    }

    public static SessionRow sessionRow(String projectId, String sessionId, String at) {
        return new SessionRow(projectId, sessionId, null, at, at, at, false);
    }

    /** A span row in the minimal ingest shape: no usage, no cost ({@code unpriced}), unresolved ancestry. */
    public static SpanRow spanRow(
            String projectId,
            String traceId,
            String spanId,
            @Nullable String parentSpanId,
            String kind,
            String startedAt,
            @Nullable String endedAt,
            String eventTs) {
        return new SpanRow(
                projectId,
                traceId,
                spanId,
                parentSpanId,
                null,
                null,
                null,
                null,
                null,
                null,
                kind,
                null,
                parentSpanId == null,
                null,
                null,
                null,
                null,
                startedAt,
                endedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SpanRow.CostSource.UNPRICED,
                null,
                null,
                null,
                SpanRow.ResolverState.PENDING,
                SpanRow.ResolverState.PENDING,
                eventTs,
                false,
                null,
                null,
                null,
                null);
    }

    // ---- seeding ----------------------------------------------------------------------------------

    public SessionRow session(String projectId, String sessionId, Instant at) {
        SessionRow row = sessionRow(projectId, sessionId, at.toString());
        sessions.getOrCreateAll(List.of(row));
        return row;
    }

    /** A trace with no session; rollup columns left null, as ingest leaves them. */
    public TraceV2Row trace(String projectId, String traceId, Instant startedAt) {
        return trace(projectId, traceId, null, startedAt);
    }

    /** A trace, get-or-creating its session first (§6.1), so {@code fk_trace_session} holds. */
    public TraceV2Row trace(String projectId, String traceId, @Nullable String sessionId, Instant startedAt) {
        return trace(projectId, traceId, sessionId, null, null, startedAt);
    }

    /**
     * A trace naming its session and {@code thread_id}; {@code COALESCE(thread_id, session_id)} is the conversation
     * key. The first write of a trace id decides its correlation, so seed the trace before its spans when that
     * matters.
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
        traces.getOrCreateAll(List.of(row));
        return row;
    }

    /** A named trace with no spans, for surfaces that render trace-level facts only. */
    public TraceV2Row namedTrace(String projectId, String traceId, @Nullable String name, Instant at) {
        TraceV2Row row = TraceV2Row.of(projectId, traceId, null, null, name, null, null, at.toString(), at.toString());
        traces.getOrCreateAll(List.of(row));
        return row;
    }

    /**
     * A span, get-or-creating its trace, with no usage or cost. {@code eventTs} is the end when there is one, else
     * the start, as the ingest mapper does.
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
        SpanRow row = spanRow(
                projectId,
                traceId,
                spanId,
                parentSpanId,
                kind,
                startedAt.toString(),
                endedAt == null ? null : endedAt.toString(),
                (endedAt == null ? startedAt : endedAt).toString());
        spans.upsertAll(List.of(row));
        return row;
    }

    /** A root LLM span of a fresh trace, the commonest seed. */
    public SpanRow llmSpan(String projectId, String traceId, Instant startedAt) {
        return span(projectId, traceId, spanId(), null, "llm", startedAt, startedAt.plusMillis(250));
    }

    /**
     * Re-write a span with usage through the production LWW upsert. {@link Long}, because null and 0 are different
     * facts.
     */
    public SpanRow withUsage(SpanRow row, @Nullable Long inputTokens, @Nullable Long outputTokens) {
        return withUsage(row, inputTokens, outputTokens, null, null, null);
    }

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
        spans.upsertAll(List.of(updated));
        return updated;
    }

    /**
     * Re-write a span with per-bucket cost; costs are decimal strings bound as {@code numeric}, never {@code double}.
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
        spans.upsertAll(List.of(updated));
        return updated;
    }

    /** Re-write a span with the previews and call site the rollup copies from the root onto the trace. */
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
        spans.upsertAll(List.of(updated));
        return updated;
    }

    /**
     * Back-date a span's ingest-clock {@code created_at}, which {@link SpanRepository} refuses to write so a producer
     * cannot move its own bill. Metering windows on it, so metering fixtures write it here rather than softening the
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

    public SpanPayloadRow payload(
            SpanRow span, @Nullable String input, @Nullable String output, @Nullable String attributesJson) {
        SpanPayloadRow row = new SpanPayloadRow(
                span.projectId(), span.traceId(), span.id(), input, output, attributesJson, null, span.eventTs());
        payloads.upsertAll(List.of(row));
        return row;
    }

    // ---- the fluent seed --------------------------------------------------------------------------

    /** A span's producer identity: {@code (trace_id, span_id)} is the only address a v2 span has. */
    public record SpanRef(String traceId, String spanId) {}

    /**
     * Start a span seed, written in FK order by {@link SpanSeed#write()}. Only what a test asserts on gets named; the
     * rest keeps the honest empty default.
     */
    public SpanSeed spanSeed(String projectId) {
        return new SpanSeed(this, projectId);
    }

    /**
     * A whole turn as one trace with one root {@code llm} span carrying the dialogue. A null {@code sessionId} is an
     * anonymous single-turn conversation, a distinct path in {@code priorTurns}.
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
     * Run a seeded trace through the real rollup: the batch timer fold (which sets {@code has_root_span}), the claim
     * (clearing {@code rollup_due_at}), and the recompute, in production's order.
     *
     * <p>The claim is spelled directly because {@code claimDue} is global and deadline-bound: using it would sleep
     * out the root deadline or race the scheduled worker.
     *
     * <p>Returns whether the trace settled; false only when it vanished.
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

    /** A {@code tool_call} row keyed by the span's producer keys, exactly what ingest writes. */
    public String toolCall(
            String projectId, SpanRef span, @Nullable String name, @Nullable String errorType, Instant at) {
        return toolCall(projectId, span, name, errorType, null, at);
    }

    /**
     * The same row with the tool's result payload: a framework that returns {@code {"error": …}} and closes the span
     * cleanly leaves a null {@code error_type}, so the failure is only in the result. Bound as {@code jsonb}, so a
     * malformed literal fails here.
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
     * A {@code retrieved_doc} row. {@code listRole} defaults to null: an unlabelled doc is kept as evidence and only
     * {@code 'candidate'} is dropped.
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
     * Fluent span seed. Correlation is written onto the span and marked {@code done} rather than left to the
     * scheduled {@code CorrelationBackfiller}, which would race the test.
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

        /** The producer's conversation id; it wins over the session as the grouping key. */
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

        /** The error class, a short label such as "TimeoutError". */
        public SpanSeed errorType(@Nullable String v) {
            this.errorType = v;
            return this;
        }

        /** The error prose, in its own column so the class stays a facet key. */
        public SpanSeed errorMessage(@Nullable String v) {
            this.errorMessage = v;
            return this;
        }

        /** Start time; the end defaults to 250ms later. */
        public SpanSeed at(Instant v) {
            this.startedAt = v;
            return this;
        }

        public SpanSeed endedAt(@Nullable Instant v) {
            this.endedAt = v;
            return this;
        }

        /**
         * No end at all: an unterminated span is a counted category, and unset {@link #endedAt} means the 250ms
         * default instead.
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

        /** Token buckets; null makes the totals read NULL. */
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

        /** Per-bucket cost as decimal strings. */
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

        /** Payload text; calling this is what makes a {@code span_payload} row exist. */
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

        /** The identity this seed will write, available before {@link #write()}. */
        public SpanRef ref() {
            return new SpanRef(traceId, spanId);
        }

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
            fx.spans.upsertAll(List.of(row));
            if (writePayload) {
                fx.payload(row, payloadInput, payloadOutput, payloadAttributes);
            }
            return row;
        }

        public SpanRef writeRef() {
            write();
            return ref();
        }
    }
}
