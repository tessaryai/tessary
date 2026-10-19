// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.ingest.CallSiteRegistry;
import ai.tessary.ingest.CallSiteResolver;
import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.MediaExternalizer;
import ai.tessary.ingest.PromptRefResolver;
import ai.tessary.ingest.PromptRefResolver.PromptRef;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.RedactionStamp;
import ai.tessary.model.ContentExtractor;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.MediaRefRepository;
import ai.tessary.storage.RetrievedDocRepository;
import ai.tessary.storage.RetrievedDocRow;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SessionRow;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.ToolCallRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only substrate writer: one drained ingest batch onto session, trace and span (substrate-model.md §6), including
 * {@code tool_call}/{@code retrieved_doc} extraction and call-site materialization. Invalid rows are dropped before the
 * transaction, never caught inside it. A span's row and the trace update reflecting it commit in ONE transaction, or a
 * settling rollup could miss the span. Locks are taken in sorted key order; {@link #commitBatch} explains the statement
 * order.
 */
@Component
public class SpanBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(SpanBatchWriter.class);

    /** Producer ids longer than this are a bug or an attack. */
    private static final int MAX_ID_CHARS = 512;

    /** A poison-row backstop, not truncation: one absurd row must not fail a whole batch. */
    private static final int MAX_PAYLOAD_CHARS = 8_000_000;

    /** The list surfaces read only these, so they are cut once, at write. */
    private static final int PREVIEW_CHARS = 200;

    private static final int MAX_PREVIEW_PARSE_CHARS = 1_000_000;

    private static final String USER_ROLE = "user";

    private static final Set<String> USER_ROLES = Set.of(USER_ROLE);

    /**
     * Message-carrier keys a typed {@code span_payload.input}/{@code output} column can hold byte-for-byte; keeping
     * both stored every prompt twice (1.5 GB of a 1.9 GB {@code span_payload}). Pricing-receipt and error-type keys are
     * never listed. The strip requires value identity, so a key sharing only a name is never touched.
     */
    private static final Set<String> PROMOTED_INPUT_KEYS =
            Set.of(GenAiAttributes.INPUT_MESSAGES, GenAiAttributes.OI_INPUT_MESSAGES, "input");

    private static final Set<String> PROMOTED_OUTPUT_KEYS =
            Set.of(GenAiAttributes.OUTPUT_MESSAGES, GenAiAttributes.OI_OUTPUT_MESSAGES, "output");

    private final SessionRepository sessions;
    private final TraceV2Repository traces;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final ToolCallRepository toolCalls;
    private final RetrievedDocRepository retrievedDocs;
    private final SpanSideTables sideTables;
    private final IngestPricer pricer;
    private final MediaExternalizer mediaExternalizer;
    private final MediaRefRepository mediaRefs;
    private final CallSiteRegistry callSites;
    private final SpanLateness lateness;
    private final ObjectMapper mapper;
    private final TransactionTemplate txn;

    private final AtomicLong writtenSpans = new AtomicLong();
    private final AtomicLong droppedSpans = new AtomicLong();

    public SpanBatchWriter(
            SessionRepository sessions,
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            ToolCallRepository toolCalls,
            RetrievedDocRepository retrievedDocs,
            SpanSideTables sideTables,
            IngestPricer pricer,
            MediaExternalizer mediaExternalizer,
            MediaRefRepository mediaRefs,
            CallSiteRegistry callSites,
            SpanLateness lateness,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.traces = traces;
        this.spans = spans;
        this.payloads = payloads;
        this.toolCalls = toolCalls;
        this.retrievedDocs = retrievedDocs;
        this.sideTables = sideTables;
        this.pricer = pricer;
        this.mediaExternalizer = mediaExternalizer;
        this.mediaRefs = mediaRefs;
        this.callSites = callSites;
        this.lateness = lateness;
        this.mapper = mapper;
        this.txn = new TransactionTemplate(transactionManager);
    }

    public long writtenSpans() {
        return writtenSpans.get();
    }

    public long droppedSpans() {
        return droppedSpans.get();
    }

    /** Relayed so the reporter need not widen package-private {@code SpanSideTables}. */
    public long unparseableToolArgs() {
        return sideTables.unparseableToolArgs();
    }

    public long unparseableToolResults() {
        return sideTables.unparseableToolResults();
    }

    /**
     * Every write is idempotent on the producer's own ids, so the caller may replay the whole batch after a failure.
     */
    public int write(String projectId, List<RawEntry> entries) {
        if (entries.isEmpty()) return 0;

        // Mutated as new ids are seen: an unknown id gets a minimal call_site row, so call sites emerge from plain-OTLP
        // traffic.
        Set<String> knownCallSites = callSites.callSiteIds(projectId);
        List<Prepared> prepared = validate(projectId, entries, knownCallSites);
        if (prepared.isEmpty()) return 0;

        // Sorted so concurrent batches take span row locks in one direction only.
        prepared.sort(Comparator.comparing((Prepared p) -> p.span().traceId())
                .thenComparing(p -> p.span().id()));

        Map<String, TraceFold> byTrace = foldTraces(prepared);
        Map<String, SessionFold> bySession = foldSessions(prepared);

        txn.executeWithoutResult(status -> commitBatch(projectId, prepared, byTrace, bySession));
        writtenSpans.addAndGet(prepared.size());
        return prepared.size();
    }

    /**
     * Nothing in here catches: a failure takes spans and trace re-arm down together, so a replay never sees a
     * half-visible state.
     */
    private void commitBatch(
            String projectId,
            List<Prepared> prepared,
            Map<String, TraceFold> byTrace,
            Map<String, SessionFold> bySession) {
        // Identity rows go first and INSIDE this transaction: committed alone, a rolled-back batch left a span-less
        // trace the reaper re-arms forever. Sorted, or opposite-order inserts deadlock. Safe ahead of the timer update
        // only because ON CONFLICT DO NOTHING takes no lock on an existing row; DO UPDATE would deadlock.
        List<Map.Entry<String, SessionFold>> newSessions = new ArrayList<>(bySession.entrySet());
        newSessions.sort(Map.Entry.comparingByKey());
        List<SessionRow> sessionRows = new ArrayList<>(newSessions.size());
        for (Map.Entry<String, SessionFold> e : newSessions) {
            SessionFold fold = e.getValue();
            sessionRows.add(new SessionRow(
                    projectId,
                    e.getKey(),
                    fold.userId(),
                    fold.startedAt().toString(),
                    fold.lastActivityAt().toString(),
                    fold.eventTs().toString(),
                    false));
        }
        sessions.getOrCreateAll(sessionRows);

        List<Map.Entry<String, TraceFold>> newTraces = new ArrayList<>(byTrace.entrySet());
        newTraces.sort(Map.Entry.comparingByKey());
        List<TraceV2Row> traceRows = new ArrayList<>(newTraces.size());
        for (Map.Entry<String, TraceFold> e : newTraces) {
            TraceFold fold = e.getValue();
            traceRows.add(TraceV2Row.of(
                    projectId,
                    e.getKey(),
                    fold.sessionId(),
                    fold.threadId(),
                    fold.name(),
                    fold.userId(),
                    fold.projectVersionId(),
                    fold.startedAt().toString(),
                    fold.eventTs().toString()));
        }
        traces.getOrCreateAll(traceRows);

        Map<String, String> rolledUpThrough = traces.rolledUpThrough(projectId, byTrace.keySet());

        // The trace timers' FOR UPDATE must come before any span insert: fk_span_trace takes KEY SHARE on the trace
        // row, and two batches each upgrading from it deadlock however keys are sorted.
        List<TraceV2Repository.TimerUpdate> timers = new ArrayList<>(byTrace.size());
        byTrace.forEach((traceId, fold) -> {
            Instant endedAt = fold.endedAt();
            timers.add(new TraceV2Repository.TimerUpdate(
                    traceId, fold.startedAt().toString(), endedAt == null ? null : endedAt.toString(), fold.hasRoot()));
        });
        traces.applyBatchTimers(projectId, timers);

        List<TraceV2Repository.CorrelationFill> fills = new ArrayList<>(byTrace.size());
        byTrace.forEach((traceId, fold) -> fills.add(new TraceV2Repository.CorrelationFill(
                traceId, fold.name(), fold.userId(), fold.sessionId(), fold.threadId())));
        List<String> filled = traces.fillCorrelation(projectId, fills);

        List<SessionRepository.Touch> touches = new ArrayList<>(bySession.size());
        bySession.forEach((sessionId, fold) -> touches.add(new SessionRepository.Touch(
                sessionId, fold.startedAt().toString(), fold.lastActivityAt().toString())));
        sessions.touchAll(projectId, touches);

        // One JDBC batch per table, in the per-span order, so locks stay sorted and FKs are satisfied table by table.
        List<SpanRow> spanRows = new ArrayList<>(prepared.size());
        List<SpanPayloadRow> payloadRows = new ArrayList<>(prepared.size());
        List<MediaRefRepository.SpanMedia> media = new ArrayList<>();
        List<ToolCallRow> toolCallRows = new ArrayList<>();
        List<RetrievedDocRow> docRows = new ArrayList<>();
        for (Prepared p : prepared) {
            spanRows.add(p.span());
            payloadRows.add(p.payload());
            // The media reference is a string inside the payload JSON, so these rows are what make the bytes reachable
            // and let them cascade out with the payload. Written after the payload upsert, which they reference.
            if (!p.mediaIds().isEmpty()) {
                media.add(new MediaRefRepository.SpanMedia(
                        p.span().traceId(), p.span().id(), p.mediaIds()));
            }
            // Same transaction as the span, or a tool call could be visible without its span.
            SpanSideTables.Extracted extracted = p.sideTables();
            ToolCallRow toolCall = extracted.toolCall();
            if (toolCall != null) {
                toolCallRows.add(toolCall);
            }
            docRows.addAll(extracted.retrievedDocs());
            recordLateness(p, rolledUpThrough.get(p.span().traceId()));
        }
        spans.upsertAll(spanRows);
        // After the upsert, so this batch's own session-less spans are covered too.
        if (!filled.isEmpty()) {
            Instant adoptStarted = Instant.now();
            int adopted = spans.adoptTraceSession(projectId, filled);
            StructuredLog.info(log, Markers.OPS, "ingest.v2.correlation.filled")
                    .message(
                            "a later batch gave %s trace(s) their session, copied onto %s span(s)",
                            filled.size(), adopted)
                    .field("projectId", projectId)
                    .field("filledTraces", filled.size())
                    .field("adoptedSpans", adopted)
                    .durationMs(adoptStarted)
                    .log();
        }
        payloads.upsertAll(payloadRows);
        mediaRefs.insertAllForSpans(projectId, media);
        toolCalls.insertAll(toolCallRows);
        retrievedDocs.insertAll(docRows);
    }

    /** A trace that never rolled up contributes no sample; first arrivals are not late. */
    private void recordLateness(Prepared p, @Nullable String rolledUpThrough) {
        if (rolledUpThrough == null) return;
        Instant through = parseOrNull(rolledUpThrough);
        if (through == null) return;
        lateness.record(Duration.between(through, p.eventTs()).toMillis());
    }

    /**
     * Drop spans no row could represent: no producer span or trace id, no parseable start, an id past
     * {@link #MAX_ID_CHARS}, or a payload past {@link #MAX_PAYLOAD_CHARS}. Counted and logged once per batch, never
     * retried, since a replay would fail identically.
     */
    private List<Prepared> validate(String projectId, List<RawEntry> entries, Set<String> knownCallSites) {
        List<Prepared> out = new ArrayList<>(entries.size());
        int noIds = 0;
        int noStart = 0;
        int oversized = 0;
        for (RawEntry raw : entries) {
            String spanId = raw.sourceExternalId();
            String traceId = raw.traceId();
            if (spanId == null || spanId.isBlank() || traceId == null || traceId.isBlank()) {
                noIds++;
                continue;
            }
            if (spanId.length() > MAX_ID_CHARS || traceId.length() > MAX_ID_CHARS) {
                oversized++;
                continue;
            }
            Instant startedAt = parseOrNull(raw.timestamp());
            if (startedAt == null) {
                noStart++;
                continue;
            }
            MediaExternalizer.Externalized inputMedia = mediaExternalizer.externalizeJson(projectId, raw.input());
            MediaExternalizer.Externalized outputMedia = mediaExternalizer.externalizeJson(projectId, raw.output());
            String input = inputMedia.payload();
            String output = outputMedia.payload();
            if (tooLarge(input) || tooLarge(output)) {
                oversized++;
                continue;
            }
            String sessionId = trimmedAttr(raw, GenAiAttributes.SESSION_ID);
            if (sessionId != null && sessionId.length() > MAX_ID_CHARS) {
                oversized++;
                continue;
            }
            out.add(prepare(
                    projectId,
                    raw,
                    traceId,
                    spanId,
                    startedAt,
                    input,
                    output,
                    mergedIds(inputMedia.mediaIds(), outputMedia.mediaIds()),
                    sessionId,
                    knownCallSites));
        }
        int dropped = noIds + noStart + oversized;
        if (dropped > 0) {
            droppedSpans.addAndGet(dropped);
            StructuredLog.warn(log, Markers.OPS, "ingest.v2.unwritable")
                    .message("dropped %s span(s) no v2 row could represent", dropped)
                    .field("projectId", projectId)
                    .field("missing_ids", noIds)
                    .field("missing_started_at", noStart)
                    .field("oversized", oversized)
                    .log();
        }
        return out;
    }

    private static boolean tooLarge(@Nullable String payload) {
        return payload != null && payload.length() > MAX_PAYLOAD_CHARS;
    }

    private Prepared prepare(
            String projectId,
            RawEntry raw,
            String traceId,
            String spanId,
            Instant startedAt,
            @Nullable String input,
            @Nullable String output,
            List<String> mediaIds,
            @Nullable String sessionId,
            Set<String> knownCallSites) {
        Instant endedAt = parseOrNull(raw.endTimestamp());
        Instant eventTs = parseOrNull(raw.eventTs());
        if (eventTs == null) eventTs = endedAt == null ? startedAt : endedAt;

        // kind is NOT NULL, and `step` is the normalized vocabulary's own word for unknown.
        String kind = raw.operationKind() == null ? KindNormalizer.STEP : raw.operationKind();
        String level = trimmedAttr(raw, "level");
        boolean isError = level != null && "ERROR".equalsIgnoreCase(level);
        String statusMessage = trimmedAttr(raw, GenAiAttributes.STATUS_MESSAGE);
        String errorTypeAttr = trimmedAttr(raw, GenAiAttributes.ERROR_TYPE);

        IngestPricer.Priced priced = pricer.price(raw, kind);
        PromptRef prompt = PromptRefResolver.resolve(raw.metadata());
        Long latencyMs =
                endedAt == null ? null : Duration.between(startedAt, endedAt).toMillis();

        SpanRow span = new SpanRow(
                projectId,
                traceId,
                spanId,
                raw.parentId(),
                // The fixpoint resolver owns path; the upsert's SET list excludes it so a redelivery cannot reset a
                // resolved span.
                null,
                sessionId,
                trimmedAttr(raw, "user.id"),
                null,
                resolveCallSite(projectId, raw, knownCallSites),
                null,
                kind,
                raw.name(),
                // A producer-declared root or a sub-agent boundary.
                raw.parentId() == null || KindNormalizer.AGENT.equals(kind),
                isError ? "error" : null,
                level,
                // The class in error_type, the prose in error_message: whole status messages never grouped.
                isError ? SpanErrors.errorClass(errorTypeAttr, statusMessage) : null,
                isError ? SpanErrors.cappedMessage(statusMessage) : null,
                startedAt.toString(),
                endedAt == null ? null : endedAt.toString(),
                latencyMs,
                null,
                raw.model(),
                priced.modelId(),
                priced.inputTokens(),
                priced.outputTokens(),
                priced.cacheReadTokens(),
                priced.cacheWriteTokens(),
                priced.reasoningTokens(),
                priced.inputCost(),
                priced.outputCost(),
                priced.cacheReadCost(),
                priced.cacheWriteCost(),
                priced.costSource(),
                priced.priceBookVersion(),
                inputPreview(input),
                preview(output),
                // A span without a session waits in the backfiller's queue until its trace answers or settles
                // session-less.
                sessionId == null ? SpanRow.ResolverState.PENDING : SpanRow.ResolverState.DONE,
                SpanRow.ResolverState.PENDING,
                eventTs.toString(),
                false,
                null,
                null,
                null,
                null,
                prompt == null ? null : prompt.name(),
                prompt == null ? null : prompt.version());

        SpanPayloadRow payload = new SpanPayloadRow(
                projectId,
                traceId,
                spanId,
                input,
                output,
                attributesJson(raw),
                priced.providedUsage(),
                eventTs.toString(),
                redactionsJson(raw));
        return new Prepared(
                span,
                payload,
                sideTables.extract(
                        projectId, traceId, spanId, kind, raw, input, output, latencyMs, eventTs.toString(), now()),
                mediaIds,
                startedAt,
                endedAt,
                eventTs,
                raw.parentId() == null,
                trimmedAttr(raw, GenAiAttributes.CONVERSATION_ID));
    }

    /** Idempotent: {@code knownCallSites} gates the write. */
    private @Nullable String resolveCallSite(String projectId, RawEntry raw, Set<String> knownCallSites) {
        String id = CallSiteResolver.resolve(raw.metadata());
        if (id != null && knownCallSites.add(id)) {
            callSites.ensureCallSite(projectId, id);
        }
        return id;
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static @Nullable String preview(@Nullable String content) {
        if (content == null) return null;
        return content.length() <= PREVIEW_CHARS ? content : content.substring(0, PREVIEW_CHARS);
    }

    /**
     * The input preview: the LAST user turn, not the head of the payload.
     *
     * <p>A head cut is worthless on real prompts. Every prompt in one corpus opened with the same
     * authored preamble, longer than {@link #PREVIEW_CHARS}, so the cut always landed inside boilerplate:
     * 4,770 traces carried exactly ONE distinct {@code input_preview} between them, while the output side
     *, which has no such preamble, had 3,820. The last user message is what the call actually asked.
     *
     * <p>{@link ContentExtractor#columnMessages} is the one parser for the role-tagged envelope, and it
     * tags a payload that is NOT one with whatever fallback role it was handed. Handing it an empty role
     * is therefore how a single parse answers both questions at once, is this an envelope, and what are
     * its user turns, and anything that is not one falls through to the plain head cut, unchanged.
     *
     * <p>The parse is skipped past {@link #MAX_PREVIEW_PARSE_CHARS}. This runs on the drain thread for
     * every span, and materializing the whole message tree of a payload allowed up to
     * {@link #MAX_PAYLOAD_CHARS} to keep 200 characters is work out of all proportion to the answer. The
     * boilerplate-preamble problem this method exists for lives in prompts of a few kilobytes.
     */
    private static @Nullable String inputPreview(@Nullable String content) {
        if (content == null) return null;
        if (content.length() > MAX_PREVIEW_PARSE_CHARS) return preview(content);
        List<ContentExtractor.RoleMessage> messages = ContentExtractor.columnMessages(content, USER_ROLES, "");
        for (int i = messages.size() - 1; i >= 0; i--) {
            ContentExtractor.RoleMessage message = messages.get(i);
            if (USER_ROLE.equals(message.role()) && !message.text().isBlank()) return preview(message.text());
        }
        return preview(content);
    }

    /**
     * The attribute bag minus a message carrier whose exact bytes the typed column holds. The test is value identity,
     * not "the column is populated": several encodings are LOSSY on the way into the column (tool calls, non-text
     * parts), and stripping by prefix deleted the last copy of them for good.
     */
    private @Nullable String redactionsJson(RawEntry raw) {
        List<RedactionStamp> stamps = raw.redactions();
        if (stamps == null || stamps.isEmpty()) return null;
        return mapper.valueToTree(stamps).toString();
    }

    private @Nullable String attributesJson(RawEntry raw) {
        Map<String, Object> attrs = raw.metadata();
        if (attrs == null || attrs.isEmpty()) return null;
        Map<String, Object> kept = new LinkedHashMap<>(attrs);
        kept.entrySet()
                .removeIf(e -> (PROMOTED_INPUT_KEYS.contains(e.getKey()) && promotedVerbatim(e.getValue(), raw.input()))
                        || (PROMOTED_OUTPUT_KEYS.contains(e.getKey()) && promotedVerbatim(e.getValue(), raw.output())));
        if (kept.isEmpty()) return null;
        return mapper.valueToTree(kept).toString();
    }

    /**
     * Compared against {@link RawEntry#input()}, not the stored string, because media externalization replaces inline
     * bytes with a handle while the content survives.
     */
    private static boolean promotedVerbatim(@Nullable Object attributeValue, @Nullable String promoted) {
        return attributeValue instanceof String s && s.equals(promoted);
    }

    private record TraceFold(
            Instant startedAt,
            @Nullable Instant endedAt,
            Instant eventTs,
            boolean hasRoot,
            @Nullable String sessionId,
            @Nullable String threadId,
            @Nullable String name,
            @Nullable String userId,
            @Nullable String projectVersionId) {}

    private record SessionFold(
            Instant startedAt,
            Instant lastActivityAt,
            Instant eventTs,
            @Nullable String userId) {}

    private Map<String, TraceFold> foldTraces(List<Prepared> prepared) {
        Map<String, TraceFold> byTrace = new LinkedHashMap<>();
        for (Prepared p : prepared) {
            SpanRow s = p.span();
            byTrace.merge(
                    s.traceId(),
                    new TraceFold(
                            p.startedAt(),
                            p.endedAt(),
                            p.eventTs(),
                            p.isRoot(),
                            s.sessionId(),
                            p.threadId(),
                            p.isRoot() ? s.name() : null,
                            s.userId(),
                            s.projectVersionId()),
                    SpanBatchWriter::mergeTrace);
        }
        return byTrace;
    }

    private static TraceFold mergeTrace(TraceFold a, TraceFold b) {
        return new TraceFold(
                earliest(a.startedAt(), b.startedAt()),
                latestNullable(a.endedAt(), b.endedAt()),
                latest(a.eventTs(), b.eventTs()),
                a.hasRoot() || b.hasRoot(),
                firstNonNull(a.sessionId(), b.sessionId()),
                firstNonNull(a.threadId(), b.threadId()),
                firstNonNull(a.name(), b.name()),
                firstNonNull(a.userId(), b.userId()),
                firstNonNull(a.projectVersionId(), b.projectVersionId()));
    }

    private Map<String, SessionFold> foldSessions(List<Prepared> prepared) {
        Map<String, SessionFold> bySession = new LinkedHashMap<>();
        for (Prepared p : prepared) {
            String sessionId = p.span().sessionId();
            if (sessionId == null) continue;
            bySession.merge(
                    sessionId,
                    new SessionFold(
                            p.startedAt(), p.eventTs(), p.eventTs(), p.span().userId()),
                    (a, b) -> new SessionFold(
                            earliest(a.startedAt(), b.startedAt()),
                            latest(a.lastActivityAt(), b.lastActivityAt()),
                            latest(a.eventTs(), b.eventTs()),
                            firstNonNull(a.userId(), b.userId())));
        }
        return bySession;
    }

    /**
     * Carries what the folds need so nothing is parsed twice. {@code threadId} is a column on {@code trace}, not a tree
     * level: sessions never nest.
     */
    private record Prepared(
            SpanRow span,
            SpanPayloadRow payload,
            SpanSideTables.Extracted sideTables,
            List<String> mediaIds,
            Instant startedAt,
            @Nullable Instant endedAt,
            Instant eventTs,
            boolean isRoot,
            @Nullable String threadId) {}

    private static @Nullable String trimmedAttr(RawEntry raw, String key) {
        Map<String, Object> attrs = raw.metadata();
        Object v = attrs == null ? null : attrs.get(key);
        if (!(v instanceof String s)) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable Instant parseOrNull(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso.trim()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(iso.trim());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static @Nullable Instant latestNullable(@Nullable Instant a, @Nullable Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return latest(a, b);
    }

    private static @Nullable String firstNonNull(@Nullable String a, @Nullable String b) {
        return a != null ? a : b;
    }

    private static List<String> mergedIds(List<String> input, List<String> output) {
        if (output.isEmpty()) return input;
        if (input.isEmpty()) return output;
        Set<String> merged = new LinkedHashSet<>(input);
        merged.addAll(output);
        return List.copyOf(merged);
    }
}
