// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.otlp;

import ai.tessary.evals.open.errors.CapabilityError;
import ai.tessary.evals.open.errors.EvalsException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The OTLP/gRPC trace receiver: the {@code TraceService/Export} server-side handler, the gRPC sibling
 * of {@code OtlpTraceController} (HTTP). gRPC delivers the request already decoded by the generated
 * stub (which ships in {@code opentelemetry-proto}), so this handler skips the HTTP path's body-size guard +
 * {@code parseFrom} and goes straight to the shared {@link OtlpIngestService}: map → span-count clamp →
 * {@code SubstrateWriter.enqueue} → OTLP response. Persisted output is therefore identical to the HTTP path —
 * sessions/turns/observations, never flat spans.
 *
 * <p><b>Auth.</b> The {@link OtlpGrpcAuthInterceptor} has already authenticated the call and stashed the
 * project id in the gRPC {@link io.grpc.Context}; an unauthenticated/non-project-scoped call is closed with
 * {@code UNAUTHENTICATED} before reaching here. <b>Enablement</b> is handled at the server-binding edge
 * ({@code config/OtlpGrpcServerConfig}) — the server only starts when {@code evals.ingest.otlp.enabled} and
 * the {@code transport} includes gRPC, so this bean is never wired into a running server when disabled.
 *
 * <p><b>Partial success for a clamp, UNAVAILABLE for a shed.</b> An oversized batch returns
 * {@code OK} with a populated {@code partial_success} (rejected-span count), exactly as the HTTP path does:
 * those spans are over a configured cap and no retry will get them in. A batch the write buffer refused is
 * the opposite case — nothing was persisted and a resend would work — so it closes with
 * {@code UNAVAILABLE}, the peer of the HTTP path's {@code 503}. Returning {@code OK} there is what let a
 * bulk upload lose spans while every export reported success.
 *
 * <p><b>{@code RESOURCE_EXHAUSTED} is reserved for the quota, not the shed.</b> The OTLP spec's
 * unconditionally retryable gRPC set is {@code CANCELLED, DEADLINE_EXCEEDED, ABORTED, OUT_OF_RANGE,
 * UNAVAILABLE, DATA_LOSS}; {@code RESOURCE_EXHAUSTED} is retryable only when the server attaches a
 * {@code google.rpc.RetryInfo}, and every stock exporter implements exactly that rule. Sent bare, it reads
 * as a permanent drop rather than "come back soon" — wrong for a buffer that may drain in seconds, which is
 * why the shed above answers {@code UNAVAILABLE} instead.
 *
 * <p>An exceeded span quota is the opposite case, and that same "reads as permanent" property is what makes
 * {@code RESOURCE_EXHAUSTED} the right answer for it: the cap will not lift until the billing period rolls,
 * so an exporter that treats a bare {@code RESOURCE_EXHAUSTED} as non-retryable-for-now is behaving exactly
 * as intended, not misled. The HTTP transport gets its status from the error catalog ({@code 402}); gRPC has
 * no such mapping, so the shared core's {@code QUOTA_EXCEEDED} is translated here to
 * {@code RESOURCE_EXHAUSTED} rather than surfacing as the {@code UNKNOWN} an unhandled exception produces,
 * and it names the quota in the description so an operator reading exporter logs sees why.
 */
@Component
public class OtlpGrpcTraceService extends TraceServiceGrpc.TraceServiceImplBase {

    private final OtlpIngestService ingestService;

    public OtlpGrpcTraceService(OtlpIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> responseObserver) {
        // Resolved + validated by OtlpGrpcAuthInterceptor, which closes the call before it reaches here when
        // the token is missing/non-project-scoped. The defensive null guard keeps this independent of the
        // interceptor being wired (a server built without it must not NPE into the substrate path).
        @Nullable String projectId = OtlpGrpcAuthInterceptor.PROJECT_ID.get();
        if (projectId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("OTLP ingest requires a project-scoped token")
                    .asRuntimeException());
            return;
        }
        // Buffer-pressure refusal, the peer of the HTTP path's pre-decode gate — with one honest
        // difference: gRPC has already deserialized the message by the time a handler runs, so this
        // refuses before mapping and enqueueing but NOT before the protobuf materialization that is the
        // memory cost the gate exists to avoid. Refusing that early needs a ServerInterceptor that closes
        // the call in its header phase; until then the HTTP front door carries the stronger guarantee and
        // this one is backpressure rather than protection.
        if (ingestService.shouldRefuse()) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("substrate write buffer under pressure — retry")
                    .asRuntimeException());
            return;
        }
        OtlpIngestService.IngestOutcome outcome;
        try {
            outcome = ingestService.ingest(projectId, request);
        } catch (EvalsException e) {
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
}
