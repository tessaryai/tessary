// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.Retryable;
import ai.tessary.open.errors.TessaryException;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.rpc.RetryInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The OTLP/gRPC sibling of {@code OtlpTraceController}, sharing {@link OtlpIngestService}. A clamp answers {@code OK}
 * with {@code partial_success}; a shed answers {@code UNAVAILABLE}, which stock exporters retry.
 * {@code RESOURCE_EXHAUSTED} without {@code RetryInfo} reads as permanent, so it is reserved for the span quota
 * ({@code QUOTA_EXCEEDED}), which will not lift until the billing period rolls.
 */
@Component
public class OtlpGrpcTraceService extends TraceServiceGrpc.TraceServiceImplBase {

    private final OtlpIngestService ingestService;

    public OtlpGrpcTraceService(OtlpIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> responseObserver) {
        // Defensive: a server built without OtlpGrpcAuthInterceptor must not NPE into the substrate path.
        @Nullable String projectId = OtlpGrpcAuthInterceptor.PROJECT_ID.get();
        if (projectId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("OTLP ingest requires a project-scoped token")
                    .asRuntimeException());
            return;
        }
        // Weaker than the HTTP gate: gRPC has already deserialized the message, so this is backpressure, not memory
        // protection. Refusing earlier needs a ServerInterceptor that closes the call in its header phase.
        if (ingestService.shouldRefuse()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("substrate write buffer under pressure — retry")
                    .asRuntimeException());
            return;
        }
        OtlpIngestService.IngestOutcome outcome;
        try {
            outcome = ingestService.ingest(projectId, request);
        } catch (TessaryException e) {
            if (e instanceof Retryable retryable) {
                responseObserver.onError(
                        retryableStatus(String.valueOf(e.getMessage()), retryable.retryAfterSeconds()));
                return;
            }
            if (e.error() != CapabilityError.QUOTA_EXCEEDED) {
                throw e;
            }
            responseObserver.onError(
                    Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        if (!outcome.accepted()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("substrate write buffer full — retry")
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(outcome.response());
        responseObserver.onCompleted();
    }
    /**
     * {@code UNAVAILABLE} with {@code RetryInfo}: the one refusal shape the OTLP spec makes every stock
     * exporter retry. {@code RESOURCE_EXHAUSTED} without it is dropped as permanent.
     */
    static StatusRuntimeException retryableStatus(String description, int retryAfterSeconds) {
        com.google.rpc.Status status = com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.UNAVAILABLE.value())
                .setMessage(description)
                .addDetails(Any.pack(RetryInfo.newBuilder()
                        .setRetryDelay(Duration.newBuilder().setSeconds(retryAfterSeconds))
                        .build()))
                .build();
        return StatusProto.toStatusRuntimeException(status);
    }
}
