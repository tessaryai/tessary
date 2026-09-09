// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.ingest.CallSiteRegistry;
import ai.tessary.ingest.CallSiteResolver;
import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.MediaExternalizer;
import ai.tessary.ingest.RawEntry;
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
import com.fasterxml.jackson.core.JsonProcessingException;
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
 * The v2 substrate write path: one drained ingest batch onto {@code session → trace → span (+
 * span_payload)}, per substrate-model.md §6.
 *
 * <h2>The only substrate writer</h2>
 *
 * <p>This is the sole writer of {@code tool_call} and {@code retrieved_doc} extraction (see
 * {@link SpanSideTables}, keyed on the producer's own pair rather than a minted observation id) and the
 * call-site materialization that lets call sites emerge from plain-OTLP traffic. No other writer mints
 * these rows; two writers touching the same rows is how duplicate data nobody can tell apart happens.
 *
 * <h2>The order, and why it is the order (§6.1)</h2>
 *
 * <ol>
 *   <li>Validate. Rows that cannot be written at all are dropped HERE, before the transaction, with a
 *       counter and one aggregated log line, never inside it. §6.1 forbids per-row catches in the batch
 *       transaction, because a catch there leaves the transaction marked rollback-only anyway and turns a
 *       clean failure into a partial write nobody can reason about.
 *   <li>Get-or-create sessions, then traces, identity fields only, as the first statements of the
 *       transaction below (see {@code commitBatch} for why they moved inside it). Both are
 *       {@code ON CONFLICT DO NOTHING}, so a redelivery is a no-op and every FK is satisfiable regardless
 *       of arrival order. Timing and rollup columns are never written here, they belong to §7 alone.
 *   <li><b>One transaction</b>: the batch-coalesced trace min/max + re-arm, the session activity fold, then
 *       the span upserts and their payload rows under the same {@code event_ts} guard.
 * </ol>
 *
 * <h2>The atomicity invariant</h2>
 *
 * <p><b>A span's row write and the trace update that reflects it commit together.</b> The settle protocol
 * (§7.4) is correct only under that invariant: a span row that became visible without its trace re-arm
 * would be silently excluded from a settling rollup, and the trace would report a total that is missing it
 * with {@code is_settled = true} claiming otherwise. It is a transaction boundary rather than a comment,
 * and it has its own test.
 *
 * <h2>Deadlock freedom</h2>
 *
 * <p>Everything this batch locks, it locks in sorted key order, trace rows, then session rows, then spans
 * by {@code (trace_id, id)}, so two concurrent batches over overlapping sets always contend in the same
 * direction: one waits, neither cycles. Sorting alone is not sufficient here, and the transaction's
 * statement order is the other half of the guarantee; {@link #commitBatch} says why.
 */
@Component
public class SpanBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(SpanBatchWriter.class);

    /** Producer ids longer than this are not identifiers, they are a bug or an attack. */
    private static final int MAX_ID_CHARS = 512;

    /**
     * Poison-row backstop for payload size. Not a truncation policy, telemetry is bound by count, never
     * clipped, but a single absurd row must not be able to fail a whole batch, and the capped FTS
     * expression on {@code span_payload} is the other half of that guard.
     */
    private static final int MAX_PAYLOAD_CHARS = 8_000_000;

    /** Preview width. The list surfaces read these and nothing else, so they are cut once, at write. */
    private static final int PREVIEW_CHARS = 200;

    /** Payload size past which {@link #inputPreview} takes the head cut rather than parsing. */
    private static final int MAX_PREVIEW_PARSE_CHARS = 1_000_000;

    /** The role whose last turn the input preview is taken from, see {@link #inputPreview}. */
    private static final String USER_ROLE = "user";

    private static final Set<String> USER_ROLES = Set.of(USER_ROLE);

    /**
     * The message-carrier attribute keys a typed {@code span_payload.input} / {@code output} column can
     * hold byte-for-byte, so keeping them in the attribute bag as well is pure duplication.
     *
     * <p>Serialising them into the attribute bag as well stored every prompt and completion TWICE: 1.5 GB
     * of a 1.9 GB {@code span_payload} was byte-for-byte duplicate, and the two message keys alone were
     * 95% of all attribute bytes. Nothing listed here overlaps {@code gen_ai.usage.*} /
     * {@code llm.token_count.*} / {@code llm.cost.*}, the pricing receipt's keys are never stripped,
     * nor {@code error.type} / {@code exception.type}, which the tool-error definition reads out of this
     * column.
     *
     * <p>The bare {@code input} / {@code output} names are here because agent-kind spans in practice carry
     * their content under them rather than under any semconv key, and the four-key set left 5,095 payloads
     * in one corpus holding a verbatim second copy, ~17 MB. Adding them is safe for the same reason the
     * rest of this set is: the strip below requires byte-for-byte value identity with the promoted column,
     * so a key that merely shares a name and not a value is never touched.
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

    /** Spans this process has written or re-written. */
    public long writtenSpans() {
        return writtenSpans.get();
    }

    /** Spans dropped by validation because no row could represent them (see {@link #validate}). */
    public long droppedSpans() {
        return droppedSpans.get();
    }

    /**
     * Tool-call arguments that arrived non-blank and were not JSON, so the typed column took null.
     * Relayed from {@code SpanSideTables}, which is package-private, so the reporter can read it without
     * widening that class's visibility for one gauge.
     */
    public long unparseableToolArgs() {
        return sideTables.unparseableToolArgs();
    }

    /** Tool-call results that arrived non-blank and were not JSON. */
    public long unparseableToolResults() {
        return sideTables.unparseableToolResults();
    }

    /**
     * Write one already-redacted batch. Throws on a backend failure, in which case the caller may replay
     * the whole batch safely: every write here is idempotent, keyed on the producer's own ids.
     *
     * @return the number of spans written.
     */
    public int write(String projectId, List<RawEntry> entries) {
        if (entries.isEmpty()) return 0;

        // Known call-site ids, loaded once per batch and mutated in place as new ones are seen. A resolved
        // id the project does not have is materialized as a minimal call_site row, so plain-OTLP telemetry
        // carrying an explicit tessary.call_site.id (no plugin-published pipeline) is not orphaned, call
        // sites emerge from traffic.
        Set<String> knownCallSites = callSites.callSiteIds(projectId);
        List<Prepared> prepared = validate(projectId, entries, knownCallSites);
        if (prepared.isEmpty()) return 0;

        // Sorted by the span key, so concurrent batches take span row locks in one direction only.
        prepared.sort(Comparator.comparing((Prepared p) -> p.span().traceId())
                .thenComparing(p -> p.span().id()));

        Map<String, TraceFold> byTrace = foldTraces(prepared);
        Map<String, SessionFold> bySession = foldSessions(prepared);

        txn.executeWithoutResult(status -> commitBatch(projectId, prepared, byTrace, bySession));
        writtenSpans.addAndGet(prepared.size());
        return prepared.size();
    }

    /**
     * The one transaction (§6.1). Nothing in here catches: a failure must take the whole batch down
     * together, spans and trace re-arm alike, so the caller replays a state that was never half-visible.
     */
    private void commitBatch(
            String projectId,
            List<Prepared> prepared,
            Map<String, TraceFold> byTrace,
            Map<String, SessionFold> bySession) {
        // IDENTITY ROWS FIRST, AND INSIDE THIS TRANSACTION.
        //
        // These two used to run before the transaction opened, on their own autocommit. That is how a
        // rolled-back batch left a trace row with no spans: the identity row committed by itself, and the
        // spans it was folded from, the only reason it exists, did not. The shell is unreachable by any
        // repair. It can never gain a span, so it can never settle, and the §7.4 reaper re-arms it every
        // grace period for the life of the database. §6.1's atomicity invariant is what forbids this, and
        // it reads on the rollup update alone only because identity was never the half that moved.
        //
        // Sorted, for the same reason the spans are. Two batches creating traces A and B in opposite orders
        // each block on the other's uncommitted insert until it commits. Outside a transaction that was a
        // momentary wait; inside one, holding locks either side of it, it is a deadlock.
        //
        // Safe ahead of the timer update below because getOrCreate is ON CONFLICT DO NOTHING, which takes
        // no lock on a row that already exists, so it cannot start the KEY SHARE the FOR UPDATE would then
        // have to upgrade. DO UPDATE here would reintroduce exactly the deadlock that comment describes.
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

        // THE TRACE TIMERS GO NEXT, AND THE ORDER IS LOAD-BEARING.
        //
        // fk_span_trace makes every span insert take a KEY SHARE lock on its trace row. Two concurrent
        // batches over the same traces can both hold that, it is a shared mode, and then both ask for the
        // FOR UPDATE the timer update opens with, which conflicts with it. That is a lock UPGRADE on a row
        // each transaction already holds, and no amount of sorted key ordering can make it safe: A waits for
        // B's KEY SHARE on the first trace while B waits for A's, and Postgres kills one of them. Taking the
        // exclusive lock before any span touches the row means each batch upgrades nothing, it already
        // holds the strongest lock it will need, and the sorted order then does its job of serializing the
        // two batches instead of crossing them.
        //
        // Ordering inside the transaction is otherwise free: §6.1 requires only that the span rows and the
        // trace re-arm become visible together, which the single commit is what guarantees.
        List<TraceV2Repository.TimerUpdate> timers = new ArrayList<>(byTrace.size());
        byTrace.forEach((traceId, fold) -> {
            Instant endedAt = fold.endedAt();
            timers.add(new TraceV2Repository.TimerUpdate(
                    traceId, fold.startedAt().toString(), endedAt == null ? null : endedAt.toString(), fold.hasRoot()));
        });
        traces.applyBatchTimers(projectId, timers);

        List<SessionRepository.Touch> touches = new ArrayList<>(bySession.size());
        bySession.forEach((sessionId, fold) -> touches.add(new SessionRepository.Touch(
                sessionId, fold.startedAt().toString(), fold.lastActivityAt().toString())));
        sessions.touchAll(projectId, touches);

        // One statement per table, not one per span: each table is a single JDBC batch, in the same
        // order the per-span loop wrote them, so the locks are taken in the same sorted order and the
        // FK from payload to span, and from media_ref to payload, is satisfied table by table.
        List<SpanRow> spanRows = new ArrayList<>(prepared.size());
        List<SpanPayloadRow> payloadRows = new ArrayList<>(prepared.size());
        List<MediaRefRepository.SpanMedia> media = new ArrayList<>();
        List<ToolCallRow> toolCallRows = new ArrayList<>();
        List<RetrievedDocRow> docRows = new ArrayList<>();
        for (Prepared p : prepared) {
            spanRows.add(p.span());
            payloadRows.add(p.payload());
            // Every image this span's payload was rewritten to reference, made visible to the database.
            // The reference itself is a string inside that JSON, so without these rows the bytes are
            // unreachable by any FK and uncollectable by retention. After the payload upsert, not
            // before: the FK is to the payload row, and it is what makes media age out with the text that
            // names it. Deleting the payload takes them with it, the cascade is the whole design.
            if (!p.mediaIds().isEmpty()) {
                media.add(new MediaRefRepository.SpanMedia(
                        p.span().traceId(), p.span().id(), p.mediaIds()));
            }
            // The side tables ride the same transaction as the span they were read off. They are keyed on
            // the same producer pair and derived from the same already-validated attributes, so there is no
            // failure mode here that the span write does not already have, and a tool call visible without
            // its span would be a row the trace-scoped detail read cannot place.
            SpanSideTables.Extracted extracted = p.sideTables();
            ToolCallRow toolCall = extracted.toolCall();
            if (toolCall != null) {
                toolCallRows.add(toolCall);
            }
            docRows.addAll(extracted.retrievedDocs());
            recordLateness(p, rolledUpThrough.get(p.span().traceId()));
        }
        spans.upsertAll(spanRows);
        payloads.upsertAll(payloadRows);
        mediaRefs.insertAllForSpans(projectId, media);
        toolCalls.insertAll(toolCallRows);
        retrievedDocs.insertAll(docRows);
    }

    /**
     * How far behind its trace's last rollup this span arrived (§7.6). A trace that has never rolled up has
     * nothing to be late relative to and contributes no sample, otherwise the histogram's first bucket
     * would just be a count of first-arrivals.
     */
    private void recordLateness(Prepared p, @Nullable String rolledUpThrough) {
        if (rolledUpThrough == null) return;
        Instant through = parseOrNull(rolledUpThrough);
        if (through == null) return;
        lateness.record(Duration.between(through, p.eventTs()).toMillis());
    }

    // ----- validation ------------------------------------------------------------------------------

    /**
     * Turn the batch into writable rows, dropping the ones no row could represent.
     *
     * <p>Four things make a span unwritable, and each is structural rather than a judgement call: no
     * producer span id or trace id (identity IS the producer's, so there is no row to key), no parseable
     * start time ({@code started_at} is NOT NULL and every timing column derives from it), an id past
     * {@link #MAX_ID_CHARS}, or a payload past {@link #MAX_PAYLOAD_CHARS}.
     *
     * <p>Dropped rows are counted and logged once per batch, categorically. They are NOT retried: a replay
     * would fail validation identically, so a retry is work with a known answer.
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

    // ----- row building ----------------------------------------------------------------------------

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

        // kind is NOT NULL in v2 while the normalized kind is an open enum that legitimately answers
        // "unknown". `step` is that vocabulary's own word for it, so the column stays honest without
        // inventing a value only this writer knows.
        String kind = raw.operationKind() == null ? KindNormalizer.STEP : raw.operationKind();
        String level = trimmedAttr(raw, "level");
        boolean isError = level != null && "ERROR".equalsIgnoreCase(level);
        String statusMessage = trimmedAttr(raw, GenAiAttributes.STATUS_MESSAGE);
        String errorTypeAttr = trimmedAttr(raw, GenAiAttributes.ERROR_TYPE);

        IngestPricer.Priced priced = pricer.price(raw, kind);
        Long latencyMs =
                endedAt == null ? null : Duration.between(startedAt, endedAt).toMillis();

        SpanRow span = new SpanRow(
                projectId,
                traceId,
                spanId,
                raw.parentId(),
                // path is the platform's, never an arrival's: the fixpoint resolver owns it, and the
                // upsert's SET list excludes it so a redelivery cannot send a resolved span back to work.
                null,
                sessionId,
                trimmedAttr(raw, "user.id"),
                null,
                resolveCallSite(projectId, raw, knownCallSites),
                null,
                kind,
                raw.name(),
                // The start of an application-level unit: a producer-declared root, or a sub-agent
                // boundary, which §9 wants queryable without inventing an entity for it.
                raw.parentId() == null || KindNormalizer.AGENT.equals(kind),
                isError ? "error" : null,
                level,
                // The class in error_type, the prose in error_message: a facet key that took the status
                // message whole was routinely kilobytes of agent markdown, and no two failures of the
                // same kind ever grouped.
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
                // A span that already carries its session has nothing to backfill; one that does not sits
                // in the backfiller's queue until its trace answers, or until the trace settles session-less.
                sessionId == null ? SpanRow.ResolverState.PENDING : SpanRow.ResolverState.DONE,
                SpanRow.ResolverState.PENDING,
                eventTs.toString(),
                false,
                null,
                null,
                null,
                null);

        SpanPayloadRow payload = new SpanPayloadRow(
                projectId,
                traceId,
                spanId,
                input,
                output,
                attributesJson(raw),
                priced.providedUsage(),
                eventTs.toString());
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

    /**
     * Resolve a span's call site and, for an id this project has not seen, materialize a minimal
     * {@code call_site} row so the span is not orphaned. Idempotent: {@code knownCallSites} gates the
     * write, so an id the plugin already published or an earlier span already created is a no-op.
     */
    private @Nullable String resolveCallSite(String projectId, RawEntry raw, Set<String> knownCallSites) {
        String id = CallSiteResolver.resolve(raw.metadata());
        if (id != null && knownCallSites.add(id)) {
            callSites.ensureCallSite(projectId, id);
        }
        return id;
    }

    /** Row-creation wall clock. Read once per row rather than once per batch; both are defensible. */
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
     * The attribute bag, minus a message carrier the typed column already holds byte-for-byte.
     *
     * <p><b>The test is value identity, not "the column is populated".</b> Those are different questions
     * and only the first one is safe to act on. {@code gen_ai.input.messages} rides into the column
     * verbatim, so dropping the key loses nothing. The other encodings are LOSSY on the way in:
     * {@code OtlpSpanMapper.reconstructIndexedMessages} reads only {@code role} and {@code content} out of
     * the Traceloop {@code gen_ai.prompt.N.*} family and leaves that family's {@code tool_calls.*} /
     * {@code finish_reason} siblings behind, and {@code OpenInferenceNormalizer} folds tool calls into
     * text on the output side ONLY, drops every non-text {@code message.contents} part, and does not read
     * the indexed {@code llm.input_messages.N.*} form at all. Stripping by prefix on a populated column
     * therefore deleted the last copy of a tool call or an image reference, permanently, since the
     * payload is rewritten at write time and never on read. A carrier whose bytes are not in the column
     * stays in the bag.
     */
    private @Nullable String attributesJson(RawEntry raw) {
        Map<String, Object> attrs = raw.metadata();
        if (attrs == null || attrs.isEmpty()) return null;
        Map<String, Object> kept = new LinkedHashMap<>(attrs);
        kept.entrySet()
                .removeIf(e -> (PROMOTED_INPUT_KEYS.contains(e.getKey()) && promotedVerbatim(e.getValue(), raw.input()))
                        || (PROMOTED_OUTPUT_KEYS.contains(e.getKey()) && promotedVerbatim(e.getValue(), raw.output())));
        if (kept.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(kept);
        } catch (JsonProcessingException e) {
            // The attribute bag is auxiliary; a span whose attributes will not serialize is still worth
            // every typed column it carries.
            log.debug("v2 span attributes dropped: not JSON-serializable", e);
            return null;
        }
    }

    /**
     * Whether the typed column was filled from this attribute value and nothing was lost doing it. Read
     * against {@link RawEntry#input()} rather than the externalized string the row stores, because media
     * externalization replaces inline bytes with a {@code MediaStore} handle, the content survives, so
     * the attribute copy is still redundant.
     */
    private static boolean promotedVerbatim(@Nullable Object attributeValue, @Nullable String promoted) {
        return attributeValue instanceof String s && s.equals(promoted);
    }

    // ----- per-batch folds -------------------------------------------------------------------------

    /** One trace's contribution from this batch: the min/max §7.1 folds in, everything else identity. */
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

    // ----- helpers ---------------------------------------------------------------------------------

    /**
     * A prepared pair plus what the per-trace and per-session folds need, so nothing is parsed twice and
     * the fold never has to reach back into the raw attribute bag.
     *
     * @param threadId the provider's conversation id, which is a column on {@code trace} rather than a
     *     second tree level, sessions never nest (§2).
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

    /**
     * The media ids of one span's input and output, deduplicated: the same image in both halves is one
     * {@code media_ref} row, and the payload references it once per side either way.
     */
    private static List<String> mergedIds(List<String> input, List<String> output) {
        if (output.isEmpty()) return input;
        if (input.isEmpty()) return output;
        Set<String> merged = new LinkedHashSet<>(input);
        merged.addAll(output);
        return List.copyOf(merged);
    }
}
