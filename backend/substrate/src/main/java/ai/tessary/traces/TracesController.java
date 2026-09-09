// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.export.SpanRowMapper;
import ai.tessary.ingest.export.TraceSpanMapper;
import ai.tessary.open.media.MediaStore;
import ai.tessary.storage.RetrievedDocRepository;
import ai.tessary.storage.RetrievedDocRow;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.ToolCallRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read API for ingested traces — the Traces UI's source of truth, one item per producer trace id.
 *
 * <h2>Every number on this wire was written before the request arrived</h2>
 *
 * <p>The list is a filter, a sort and a page over {@code trace}'s rollup columns, and nothing else. Span
 * counts, token buckets and costs are read off the listed row exactly as the rollup worker
 * (substrate-model.md §7.2) wrote them; the request performs no {@code GROUP BY}, no pricing arithmetic
 * and no payload read. The previous implementation aggregated every observation in the project through an
 * unscoped {@code GROUP BY o2.trace_id}, priced the result against an inlined catalogue CTE, and reached
 * into {@code message_block} twice per row for previews — that query is what this endpoint was named for
 * and it is gone.
 *
 * <p>The row and page shapes live in {@link TraceDtos}, and the keyset cursor plus the over-fetch-by-one
 * next-page detection in {@link TracePageCodec}. MCP's {@code list_traces} serves the same page from the same
 * repository seam, and one encoder is the only thing that keeps the two surfaces' cursors interchangeable.
 *
 * <h2>Three ways a number can be absent, and the wire keeps them apart</h2>
 *
 * <ul>
 *   <li>{@code is_settled = false} — the trace is still receiving spans (or has never rolled up). Its
 *       totals are provisional, and a UI that renders them as final is lying about a live turn.
 *   <li>settled with a null total — every span reported, and none of them consumed tokens. Genuinely
 *       nothing, not unknown.
 *   <li>{@code unpriced_spans > 0} — the total is real but incomplete: some span ran a model we hold no
 *       rate for, so a low figure must not be read as a cheap turn.
 * </ul>
 *
 * <p>All three used to render as the same em dash.
 *
 * <h2>Identity</h2>
 *
 * <p>Path ids are the producer's trace id, and only that. The transitional shim that resolved a legacy v1
 * ULID through {@code substrate_v2_id_map} retired with the map in the teardown release: a deep link
 * minted before the cutover now reaches the 404 rather than being translated. That is the end of the
 * transition the shim existed to cover, not a gap in it.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/traces")
public class TracesController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TraceV2Repository traces;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final ToolCallRepository toolCalls;
    private final RetrievedDocRepository retrievalDocuments;
    private final TenantPathResolver resolver;
    private final MediaStore media;

    public TracesController(
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            ToolCallRepository toolCalls,
            RetrievedDocRepository retrievalDocuments,
            TenantPathResolver resolver,
            MediaStore media) {
        this.traces = traces;
        this.spans = spans;
        this.payloads = payloads;
        this.toolCalls = toolCalls;
        this.retrievalDocuments = retrievalDocuments;
        this.resolver = resolver;
        this.media = media;
    }

    /** A tool invocation on a tool span: structured args/result, plus error/retries/latency. */
    public record ToolCallView(
            @Nullable String name,
            @Nullable String args,
            @Nullable String result,
            @Nullable String error,
            @Nullable Integer retries,
            @JsonProperty("latency_ms") @Nullable Long latencyMs) {}

    /** One retrieved passage of a RAG (retrieval) span. */
    public record RetrievalDocumentView(
            @Nullable Integer seq,
            @JsonProperty("doc_id") @Nullable String docId,
            @Nullable String content,
            @Nullable Double score) {}

    /**
     * One step inside a trace — the v2 {@code span}, with its typed usage and cost buckets.
     *
     * <p>{@code cost_source} is the only correct way to read a null cost, and it ships with every span for
     * exactly that reason: {@code provided} means the producer sent the figure, {@code inferred} means we
     * priced it under {@code price_book_version}, {@code unpriced} means we hold no rate and the columns
     * are null rather than zero.
     *
     * <p>{@code path} is materialized ancestry and {@code depth} derives from it. <b>A null path means
     * unresolved, never root</b> — root is {@code parent_span_id == null}, the producer's own statement.
     *
     * <p>{@code payload_available} tells a viewer whether {@code input}/{@code output}/{@code attributes}
     * are absent because the span carried none or because retention purged the payload row ahead of the
     * span, which it deliberately does (§10).
     */
    public record SpanView(
            @JsonProperty("trace_id") String traceId,
            String id,
            @JsonProperty("parent_span_id") @Nullable String parentSpanId,
            @Nullable String path,
            @Nullable Integer depth,
            @Nullable String kind,
            @Nullable String name,
            @JsonProperty("is_logical_root") boolean isLogicalRoot,
            @Nullable String model,
            @JsonProperty("model_id") @Nullable String modelId,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("ended_at") @Nullable String endedAt,
            @JsonProperty("duration_ms") @Nullable Long durationMs,
            @JsonProperty("ttft_ms") @Nullable Long ttftMs,
            @Nullable String status,
            @Nullable String level,
            @JsonProperty("error_type") @Nullable String errorType,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            @JsonProperty("input_tokens") @Nullable Long inputTokens,
            @JsonProperty("output_tokens") @Nullable Long outputTokens,
            @JsonProperty("cache_read_tokens") @Nullable Long cacheReadTokens,
            @JsonProperty("cache_write_tokens") @Nullable Long cacheWriteTokens,
            @JsonProperty("reasoning_tokens") @Nullable Long reasoningTokens,
            @JsonProperty("total_tokens") @Nullable Long totalTokens,
            @JsonProperty("input_cost") @Nullable BigDecimal inputCost,
            @JsonProperty("output_cost") @Nullable BigDecimal outputCost,
            @JsonProperty("total_cost") @Nullable BigDecimal totalCost,
            @JsonProperty("cost_source") String costSource,
            @JsonProperty("price_book_version") @Nullable String priceBookVersion,
            @Nullable String input,
            @Nullable String output,
            @Nullable Map<String, Object> attributes,
            @JsonProperty("payload_available") boolean payloadAvailable,
            @JsonProperty("tool_calls") List<ToolCallView> toolCalls,
            @JsonProperty("retrieval_documents") List<RetrievalDocumentView> retrievalDocuments) {}

    /**
     * Full trace detail: the same rollup row the list serves, plus the trace's spans.
     *
     * <p>The rollups ride along deliberately. Without them the detail view would have to sum its spans to
     * put a total on the header, which is the read-time arithmetic this schema exists to delete — and it
     * would disagree with the list row for any trace whose spans are still arriving.
     */
    public record TraceDetail(TraceDtos.TraceListItem trace, List<SpanView> spans) {}

    @GetMapping
    public ApiResponse<TraceDtos.TracesPage> list(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String model,
            @RequestParam(required = false) @Nullable String kind,
            // Keeps a trace when any of its spans carries this call site. An untagged span has none, so it
            // never matches — a semi-join, not an aggregate.
            @RequestParam(required = false) @Nullable String callSite,
            @RequestParam(required = false) @Nullable String fromTimestamp,
            @RequestParam(required = false) @Nullable String toTimestamp,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String q,
            // when (default) | tokens | cost | latency — each an indexed rollup column, NULLS LAST so a
            // trace that has not rolled up sorts to the end rather than to either extreme.
            @RequestParam(required = false) @Nullable String sort) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view traces");

        TracePageCodec.Key before = TracePageCodec.decode(cursor);
        var query = new TraceV2Repository.TraceQuery(model, kind, callSite, fromTimestamp, toTimestamp, status, q);
        int pageSize = TracePageCodec.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        String projectId = r.project().id();

        // Over-fetch by one so the codec can detect a next page without a second COUNT query.
        List<TraceV2Repository.Summary> rows =
                traces.list(projectId, query, sort, pageSize + 1, before.sortValue(), before.startedAt(), before.id());
        TracePageCodec.Page page = TracePageCodec.trim(rows, pageSize, sort);

        return ApiResponse.ok(new TraceDtos.TracesPage(
                page.rows().stream().map(TraceDtos::item).toList(), page.nextCursor()));
    }

    @GetMapping("/{traceId}")
    public ApiResponse<TraceDetail> detail(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String traceId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view a trace");
        String projectId = r.project().id();

        TraceV2Repository.Summary trace = traces.findSummary(projectId, traceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "trace not found"));

        // Three reads for the whole trace, then grouped in memory — never an N+1 per span. The payload
        // join is the ONE place this API touches span_payload; no list surface does.
        Map<String, SpanPayloadRow> payloadBySpan = new HashMap<>();
        for (SpanPayloadRow p : payloads.listByTrace(projectId, traceId)) {
            payloadBySpan.put(p.spanId(), p);
        }
        Map<String, List<ToolCallView>> toolsBySpan = new HashMap<>();
        for (ToolCallRepository.SpanToolCall t : toolCalls.listByTrace(projectId, traceId)) {
            if (t.spanId() != null) {
                toolsBySpan.computeIfAbsent(t.spanId(), k -> new ArrayList<>()).add(toToolCall(t.row()));
            }
        }
        Map<String, List<RetrievalDocumentView>> docsBySpan = new HashMap<>();
        for (RetrievedDocRepository.SpanRetrievedDoc d : retrievalDocuments.listByTrace(projectId, traceId)) {
            if (d.spanId() != null) {
                docsBySpan.computeIfAbsent(d.spanId(), k -> new ArrayList<>()).add(toRetrievalDocument(d.row()));
            }
        }

        List<SpanView> views = spans.listByTrace(projectId, traceId).stream()
                .map(s -> toSpan(
                        s,
                        payloadBySpan.get(s.id()),
                        toolsBySpan.getOrDefault(s.id(), List.of()),
                        docsBySpan.getOrDefault(s.id(), List.of())))
                .toList();
        return ApiResponse.ok(new TraceDetail(TraceDtos.item(trace), views));
    }

    /**
     * Real binary preservation for a trace's export (#986, Epic 8 Track B) — one OTel GenAI span per
     * JSONL line, in the shape the evals plugin's Path A consumes ({@link TraceSpanMapper}). This is the
     * caller {@code TraceSpanMapper.toSpan}/{@code toSpanLine} had none of before this issue: those
     * methods existed only for {@code OpenInferenceNormalizerTest}/{@code TraceSpanMapperTest} to call.
     *
     * <p>Deliberately the existing, already-authenticated per-project route (this class's own
     * convention for a per-trace read), not a new public/token-scoped endpoint — that would be new
     * auth/DoS surface this issue never asked for. Deliberately not a referenced {@code /media/{id}}
     * export either (Epic 8 Track B Fork 2): media is inlined as a base64 {@code data:} URI inside the
     * existing plain-string {@code content} field ({@link TraceSpanMapper#toSpan}), so this response
     * carries no separate media URLs and needs no org/project-slug-to-URL plumbing.
     *
     * <p>Reuses the exact two reads {@link #detail} already performs ({@code SpanRepository} +
     * {@code SpanPayloadRepository}, no {@code span_payload} touch beyond what a single-trace open
     * already costs) rather than adding a third query shape for the same rows.
     */
    @GetMapping(value = "/{traceId}/export", produces = "application/x-ndjson")
    public ResponseEntity<String> export(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String traceId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "export a trace");
        String projectId = r.project().id();

        List<SpanRow> spanRows = spans.listByTrace(projectId, traceId);
        if (spanRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace not found");
        }
        Map<String, SpanPayloadRow> payloadBySpan = new HashMap<>();
        for (SpanPayloadRow p : payloads.listByTrace(projectId, traceId)) {
            payloadBySpan.put(p.spanId(), p);
        }

        StringBuilder ndjson = new StringBuilder();
        for (SpanRow s : spanRows) {
            RawEntry raw = SpanRowMapper.toRawEntry(s, payloadBySpan.get(s.id()), MAPPER);
            ndjson.append(TraceSpanMapper.toSpanLine(raw, projectSlug, media, projectId))
                    .append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(ndjson.toString());
    }

    /**
     * Package-visible, not private: {@link SessionsController}'s spans read shares this exact assembly
     * rather than a second one that could quietly drift from it (the payload-availability/cost-source
     * honesty fields especially — see the class javadoc).
     */
    static SpanView toSpan(
            SpanRow s,
            @Nullable SpanPayloadRow payload,
            List<ToolCallView> toolCalls,
            List<RetrievalDocumentView> retrievalDocuments) {
        return new SpanView(
                s.traceId(),
                s.id(),
                s.parentSpanId(),
                s.path(),
                s.depth(),
                s.kind(),
                s.name(),
                s.isLogicalRoot(),
                s.providedModelName(),
                s.modelId(),
                s.startedAt(),
                s.endedAt(),
                s.latencyMs(),
                s.ttftMs(),
                s.status(),
                s.level(),
                s.errorType(),
                s.callSiteId(),
                s.inputTokens(),
                s.outputTokens(),
                s.cacheReadTokens(),
                s.cacheWriteTokens(),
                s.reasoningTokens(),
                s.totalTokens(),
                decimal(s.inputCost()),
                decimal(s.outputCost()),
                decimal(s.totalCost()),
                s.costSource(),
                s.priceBookVersion(),
                payload == null ? null : payload.input(),
                payload == null ? null : payload.output(),
                payload == null ? null : attributesMap(parseObject(payload.attributes())),
                payload != null,
                toolCalls,
                retrievalDocuments);
    }

    private static @Nullable BigDecimal decimal(@Nullable String numeric) {
        return numeric == null ? null : new BigDecimal(numeric);
    }

    /** The parsed attribute bag as a plain {@code Map} for the JSON response, or null when absent. */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null = no bag → the field is omitted
    private static @Nullable Map<String, Object> attributesMap(@Nullable JsonNode attrs) {
        if (attrs == null || !attrs.isObject() || attrs.isEmpty()) return null;
        return MAPPER.convertValue(attrs, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private static @Nullable JsonNode parseObject(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isObject() ? n : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /** Package-visible alongside {@link #toSpan} — {@link SessionsController}'s spans read shares it. */
    static RetrievalDocumentView toRetrievalDocument(RetrievedDocRow d) {
        return new RetrievalDocumentView(d.seq(), d.docId(), d.content(), d.score());
    }

    /** Package-visible alongside {@link #toSpan} — {@link SessionsController}'s spans read shares it. */
    static ToolCallView toToolCall(ToolCallRow t) {
        // Prefer the structured jsonb args/result; fall back to the verbatim arguments_raw.
        String args = t.arguments() != null ? t.arguments() : t.argumentsRaw();
        return new ToolCallView(t.name(), args, t.result(), t.errorType(), t.retries(), t.latencyMs());
    }
}
