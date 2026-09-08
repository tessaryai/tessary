// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.otlp;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.config.OtlpReceiverProperties;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.IngestError;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live OTLP/HTTP trace receiver — the push front door for production traffic. It accepts a
 * standard OTLP/HTTP {@code POST /v1/traces} ({@code application/x-protobuf}
 * {@code ExportTraceServiceRequest}), normalizes each span at the edge to the canonical
 * {@code gen_ai.*} schema, and tees the batch onto the agent-native substrate spine
 * ({@code session → turn → trace → typed observation (+ tool_call / message)}) through the existing
 * async write path — emitting <b>sessions/turns/observations, never flat raw spans</b>. That is the
 * differentiator over a generic OTel span sink.
 *
 * <p><b>Always on.</b> OTLP ingest is a core feature: the HTTP front door always serves (it requires a
 * valid write-scoped token, so it is not an open endpoint). The only way it goes dark is a
 * {@code grpc}-only deployment ({@code evals.ingest.otlp.transport=grpc}), where this HTTP route returns
 * {@code 404}. The sibling gRPC transport ({@code OtlpGrpcTraceService}) tees into the same substrate
 * via the shared {@link OtlpIngestService}; both decode → map → enqueue along one path, so persisted
 * output is identical.
 *
 * <p><b>Auth + project resolution.</b> The receiver authenticates a project-scoped MCP token
 * ({@code Authorization: Bearer tsy_...}) resolved by {@code AuthFilter} to a {@link TenantContext}
 * carrying the project id — no org/project URL path is needed, which is what lets a stock OTLP exporter
 * (which sets headers, not path segments) push with no re-instrumentation. {@code /v1/traces} is not
 * under {@code /api/**}, so {@code AuthFilter} does not hard-401 it; this controller enforces the
 * project-scoped-token requirement itself.
 *
 * <p><b>Non-blocking, no grading run.</b> The batch is handed to {@code SubstrateWriter.enqueue}
 * (shed-not-block, idempotent) and the call returns immediately; unlike the JSONL upload path it creates
 * <em>no</em> grading {@code run} — it writes the run-independent substrate only.
 *
 * <p><b>A shed batch answers {@code 503} + {@code Retry-After}, never {@code 200}.</b> When the write
 * buffer is full the spans were not persisted, and 200 would tell the exporter to drop them from its own
 * buffer and move on — the shape of the bulk upload that lost 5,835 spans while every POST succeeded. 503
 * is OTLP's retryable signal: a stock exporter re-sends on it unprompted, and the write path is idempotent,
 * so the resend closes the gap instead of duplicating it. {@code partial_success} is deliberately NOT used
 * for this — it means "these spans are gone, do not resend", which is true of the span-count clamp and
 * false of a shed.
 */
@RestController
@RequestMapping("/v1/traces")
public class OtlpTraceController {

    /** OTLP/HTTP protobuf content type. */
    private static final String PROTOBUF = "application/x-protobuf";

    /**
     * {@code Retry-After} on a shed batch. Short on purpose: the write buffer is an in-process queue that
     * drains in well under a second, so this is "wait for the drainer to catch up", not "come back later".
     */
    private static final String RETRY_AFTER_SECONDS = "1";

    private final OtlpReceiverProperties props;
    private final OtlpIngestService ingestService;

    public OtlpTraceController(OtlpReceiverProperties props, OtlpIngestService ingestService) {
        this.props = props;
        this.ingestService = ingestService;
    }

    @PostMapping(consumes = PROTOBUF, produces = PROTOBUF)
    // Declaring one response replaces springdoc's generated set rather than merging into it, so the 200
    // is spelled out here beside the 503 it would otherwise displace.
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Accepted; partial_success is populated when the batch was clamped.",
                content = @Content(mediaType = PROTOBUF, schema = @Schema(type = "string", format = "byte"))),
        @ApiResponse(
                responseCode = "503",
                description = "Write buffer full — nothing was persisted; resend after Retry-After seconds.",
                content = @Content,
                headers =
                        @Header(
                                name = HttpHeaders.RETRY_AFTER,
                                description = "Seconds to wait before resending the same batch.",
                                schema = @Schema(type = "integer")))
    })
    public ResponseEntity<byte[]> export(TenantContext ctx, @RequestBody byte[] body) {
        if (!props.getTransport().includesHttp()) {
            // Only dark on a gRPC-only deployment — behave as if the HTTP route does not exist (404).
            throw new EvalsException(IngestError.OTLP_DISABLED);
        }
        // The token must be project-scoped: a stock exporter pushes with a Bearer ingest token, not a
        // browser cookie session (which carries no project until a path resolves one).
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            throw new EvalsException(IngestError.OTLP_TOKEN_REQUIRED);
        }
        // Least-privilege key family: a write or mcp key may ingest; a query-only key may not.
        if (!ctx.keyPermits(ai.tessary.evals.tenant.KeyScope.WRITE)) {
            throw new EvalsException(IngestError.OTLP_WRONG_KEY_SCOPE);
        }

        // Refuse on live buffer pressure BEFORE decoding. A static byte cap bounds one push; this bounds
        // the aggregate, which is what actually ran the heap out — the batches that killed ingest were
        // each well under the cap, and the OutOfMemoryError landed inside parseFrom, upstream of every
        // protection the write path has.
        if (ingestService.shouldRefuse()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .build();
        }

        // Bound peak memory on this inbound surface: the body is already buffered into a byte[], but
        // reject before parseFrom so the protobuf materialization can't pile on top of an oversized push.
        int maxBytes = props.getMaxBodyBytes();
        if (maxBytes > 0 && body.length > maxBytes) {
            throw new EvalsException(IngestError.OTLP_BODY_TOO_LARGE, maxBytes);
        }

        ExportTraceServiceRequest request;
        try {
            request = ExportTraceServiceRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new EvalsException(IngestError.OTLP_MALFORMED_BODY, e);
        }

        // Shared mapping → span-count clamp → substrate enqueue → OTLP response. The gRPC transport
        // calls the same collaborator, so both front doors persist identical substrate output.
        OtlpIngestService.IngestOutcome outcome = ingestService.ingest(projectId, request);
        if (!outcome.accepted()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .build();
        }
        return ResponseEntity.ok(outcome.response().toByteArray());
    }
}
