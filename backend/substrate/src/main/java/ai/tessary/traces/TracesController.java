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
import ai.tessary.storage.TraceDetectionRepository;
import ai.tessary.storage.TraceFilters;
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
 * Every number on this wire was written by the rollup worker before the request; the list does no GROUP BY, pricing or
 * payload read. Unsettled, settled-null and {@code unpriced_spans > 0} are three distinct absences the wire keeps
 * apart. Path ids are the producer's trace id only. Cursors share {@link TracePageCodec} with MCP's
 * {@code list_traces}.
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
    private final TraceDetectionRepository detections;

    public TracesController(
            TraceV2Repository traces,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            ToolCallRepository toolCalls,
            RetrievedDocRepository retrievalDocuments,
            TenantPathResolver resolver,
            MediaStore media,
            TraceDetectionRepository detections) {
        this.detections = detections;
        this.traces = traces;
        this.spans = spans;
        this.payloads = payloads;
        this.toolCalls = toolCalls;
        this.retrievalDocuments = retrievalDocuments;
        this.resolver = resolver;
        this.media = media;
    }

    public record ToolCallView(
            @Nullable String name,
            @Nullable String args,
            @Nullable String result,
            @Nullable String error,
            @Nullable Integer retries,
            @JsonProperty("latency_ms") @Nullable Long latencyMs) {}

    public record RetrievalDocumentView(
            @Nullable Integer seq,
            @JsonProperty("doc_id") @Nullable String docId,
            @Nullable String content,
            @Nullable Double score) {}

    /**
     * {@code cost_source} is the only correct way to read a null cost. A null {@code path} means unresolved, never
     * root. {@code payload_available} distinguishes "carried none" from "purged by retention".
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
     * The rollups ride along so the header need not sum spans, which would disagree with the list row while spans
     * arrive.
     */
    public record TraceDetail(
            TraceDtos.TraceListItem trace, List<SpanView> spans, List<TraceDtos.DetectionMark> detections) {}

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
            // true keeps a trace when any span carries a call site, false when none does.
            @RequestParam(required = false) @Nullable Boolean hasCallSite,
            @RequestParam(required = false) @Nullable String fromTimestamp,
            @RequestParam(required = false) @Nullable String toTimestamp,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String q,
            // when (default) | tokens | cost | latency — each an indexed rollup column, NULLS LAST so a
            // trace that has not rolled up sorts to the end rather than to either extreme.
            @RequestParam(required = false) @Nullable String sort,
            // Classifier ids, comma-separated: keeps a trace every one of them flagged. "any" alone keeps a
            // trace any classifier flagged.
            @RequestParam(required = false) @Nullable List<String> detectedBy) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view traces");

        TracePageCodec.Key before = TracePageCodec.decode(cursor);
        var query = new TraceV2Repository.TraceQuery(
                model,
                kind,
                callSite,
                hasCallSite,
                fromTimestamp,
                toTimestamp,
                status,
                q,
                TraceFilters.detectedBy(detectedBy));
        int pageSize = TracePageCodec.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        String projectId = r.project().id();

        // Over-fetch by one so the codec can detect a next page without a second COUNT query.
        List<TraceV2Repository.Summary> rows =
                traces.list(projectId, query, sort, pageSize + 1, before.sortValue(), before.startedAt(), before.id());
        TracePageCodec.Page page = TracePageCodec.trim(rows, pageSize, sort);

        List<String> ids =
                page.rows().stream().map(TraceV2Repository.Summary::id).toList();
        return ApiResponse.ok(new TraceDtos.TracesPage(
                TraceDtos.items(page.rows(), detections.forTraces(projectId, ids)), page.nextCursor()));
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

        // Three reads, grouped in memory, never an N+1. The only place this API touches span_payload.
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
        List<TraceDetectionRepository.Mark> marks = detections.forTraces(projectId, List.of(traceId));
        return ApiResponse.ok(
                new TraceDetail(TraceDtos.items(List.of(trace), marks).get(0), views, TraceDtos.marks(marks)));
    }

    /**
     * The existing authenticated per-project route, not a new public endpoint. Media is inlined as base64 {@code data:}
     * URIs, so no media URLs are needed.
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

    /** Package-visible so {@link SessionsController} shares this assembly rather than one that could drift. */
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

    // null = no bag → the field is omitted
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

    static RetrievalDocumentView toRetrievalDocument(RetrievedDocRow d) {
        return new RetrievalDocumentView(d.seq(), d.docId(), d.content(), d.score());
    }

    static ToolCallView toToolCall(ToolCallRow t) {
        String args = t.arguments() != null ? t.arguments() : t.argumentsRaw();
        return new ToolCallView(t.name(), args, t.result(), t.errorType(), t.retries(), t.latencyMs());
    }
}
