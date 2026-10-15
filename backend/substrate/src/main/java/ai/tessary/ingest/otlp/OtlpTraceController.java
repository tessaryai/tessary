// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import ai.tessary.auth.TenantContext;
import ai.tessary.config.OtlpReceiverProperties;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
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
 * The OTLP/HTTP {@code POST /v1/traces} receiver; the gRPC transport shares {@link OtlpIngestService}. Not under
 * {@code /api/**}, so this controller enforces the project-scoped token itself. A shed batch answers 503 with
 * {@code Retry-After}, never 200 or {@code partial_success}: a stock exporter retries 503, and the write path is
 * idempotent.
 */
@RestController
@RequestMapping("/v1/traces")
public class OtlpTraceController {

    private static final String PROTOBUF = "application/x-protobuf";

    /** The write buffer drains in well under a second. */
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
            // 404 on a gRPC-only deployment.
            throw new TessaryException(IngestError.OTLP_DISABLED);
        }
        // A stock exporter pushes with a Bearer ingest token; a cookie session carries no project.
        String projectId = ctx.projectId();
        if (!ctx.isMcpToken() || projectId == null) {
            throw new TessaryException(IngestError.OTLP_TOKEN_REQUIRED);
        }
        // Least-privilege key family: a write or mcp key may ingest; a query-only key may not.
        if (!ctx.keyPermits(ai.tessary.tenant.KeyScope.WRITE)) {
            throw new TessaryException(IngestError.OTLP_WRONG_KEY_SCOPE);
        }

        // Refuse on buffer pressure before decoding: the OutOfMemoryError that killed ingest landed in parseFrom, under
        // the byte cap.
        if (ingestService.shouldRefuse()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .build();
        }

        // Reject before parseFrom so protobuf materialization can't pile onto an oversized push.
        int maxBytes = props.getMaxBodyBytes();
        if (maxBytes > 0 && body.length > maxBytes) {
            throw new TessaryException(IngestError.OTLP_BODY_TOO_LARGE, maxBytes);
        }

        ExportTraceServiceRequest request;
        try {
            request = ExportTraceServiceRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new TessaryException(IngestError.OTLP_MALFORMED_BODY, e);
        }

        OtlpIngestService.IngestOutcome outcome = ingestService.ingest(projectId, request);
        if (!outcome.accepted()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .build();
        }
        return ResponseEntity.ok(outcome.response().toByteArray());
    }
}
