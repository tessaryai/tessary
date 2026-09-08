// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.evals.auth.BearerTokenAuthenticator;
import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.config.OtlpReceiverProperties;
import ai.tessary.evals.config.SubstrateProperties;
import ai.tessary.evals.ingest.GenAiAttributes;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.ingest.substrate.SubstrateWriter;
import ai.tessary.evals.open.errors.CapabilityError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.tenant.KeyScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * End-to-end test of the OTLP/gRPC receiver: boots the real {@link OtlpGrpcTraceService} +
 * {@link OtlpGrpcAuthInterceptor} on an ephemeral port (no external infra), then drives it with the in-jar
 * {@link TraceServiceGrpc} blocking stub. Asserts the full decode → {@link RawEntry} → {@code enqueue} path
 * runs under the token-resolved project id, and that a missing/non-project-scoped token is rejected with
 * {@code UNAUTHENTICATED} before the substrate is ever touched. {@link SubstrateWriter} and
 * {@link BearerTokenAuthenticator} are mocked so the test is hermetic and fast.
 */
class OtlpGrpcTraceServiceTest {

    private static final String VALID_TOKEN = "tsy_w_validtoken";

    private final SubstrateWriter substrateWriter = Mockito.mock(SubstrateWriter.class);
    private final BearerTokenAuthenticator bearerAuth = Mockito.mock(BearerTokenAuthenticator.class);

    private Server server;
    private ManagedChannel channel;

    /** Set by a test to make the shared core's quota gate refuse; null (the default) admits every push. */
    private volatile @Nullable EvalsException quotaFailure;

    @BeforeEach
    void startServer() throws IOException {
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        OtlpSpanMapper mapper = new OtlpSpanMapper(new ObjectMapper());
        OtlpIngestService ingest =
                new OtlpIngestService(props, new SubstrateProperties(), substrateWriter, mapper, projectId -> {
                    EvalsException failure = quotaFailure;
                    if (failure != null) {
                        throw failure;
                    }
                });
        OtlpGrpcTraceService service = new OtlpGrpcTraceService(ingest);
        OtlpGrpcAuthInterceptor interceptor = new OtlpGrpcAuthInterceptor(bearerAuth);

        server = NettyServerBuilder.forAddress(new InetSocketAddress("localhost", 0))
                .addService(service)
                .intercept(interceptor)
                .build()
                .start();
        channel = NettyChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private TraceServiceGrpc.TraceServiceBlockingStub stub(String bearerToken) {
        Metadata md = new Metadata();
        md.put(OtlpGrpcAuthInterceptor.AUTHORIZATION, "Bearer " + bearerToken);
        Channel intercepted = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(md));
        return TraceServiceGrpc.newBlockingStub(intercepted);
    }

    private static ExportTraceServiceRequest request() {
        Span span = Span.newBuilder()
                .setName("chat")
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(KeyValue.newBuilder()
                        .setKey(GenAiAttributes.OPERATION_NAME)
                        .setValue(AnyValue.newBuilder()
                                .setStringValue(GenAiAttributes.OP_CHAT)
                                .build())
                        .build())
                .build();
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(span))
                        .build())
                .build();
    }

    private static TenantContext projectToken(String projectId) {
        return new TenantContext("user-1", null, "org-1", projectId, "member", "tok-1");
    }

    @Test
    void validProjectToken_decodesAndEnqueuesUnderResolvedProject() {
        when(bearerAuth.authenticate(eq("Bearer " + VALID_TOKEN))).thenReturn(Optional.of(projectToken("proj-grpc")));
        when(substrateWriter.enqueue(eq("proj-grpc"), any())).thenReturn(true);

        ExportTraceServiceResponse response = stub(VALID_TOKEN).export(request());

        assertFalse(response.hasPartialSuccess(), "a single-span batch is fully accepted");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(substrateWriter).enqueue(eq("proj-grpc"), captor.capture());
        assertEquals(1, captor.getValue().size(), "the decoded span is enqueued under the token's project");
        assertEquals(Set.of("01"), Set.of(captor.getValue().get(0).sourceExternalId()));
    }

    @Test
    void unresolvableToken_rejectedUnauthenticated_andNeverTouchesSubstrate() {
        when(bearerAuth.authenticate(anyString())).thenReturn(Optional.empty());

        StatusRuntimeException ex = assertThrows(
                StatusRuntimeException.class, () -> stub("badtoken").export(request()));
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        verify(substrateWriter, never()).enqueue(anyString(), any());
    }

    @Test
    void nonProjectScopedToken_rejectedUnauthenticated() {
        // A user-session-shaped context (no project id, not an MCP token) must be refused on the ingest path.
        when(bearerAuth.authenticate(anyString()))
                .thenReturn(Optional.of(new TenantContext("user-1", null, "org-1", null, "owner", null)));

        StatusRuntimeException ex = assertThrows(
                StatusRuntimeException.class, () -> stub(VALID_TOKEN).export(request()));
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        verify(substrateWriter, never()).enqueue(anyString(), any());
    }

    @Test
    void queryScopedToken_rejectedPermissionDenied_andNeverTouchesSubstrate() {
        // A project-scoped but query-only key must not be able to ingest over gRPC — parity with the HTTP
        // ingest path's keyPermits(WRITE) gate (otherwise gRPC would be a scope-bypass for query-only keys).
        when(bearerAuth.authenticate(anyString()))
                .thenReturn(Optional.of(
                        new TenantContext("user-1", null, "org-1", "proj-grpc", "member", "tok-1", KeyScope.QUERY)));

        StatusRuntimeException ex = assertThrows(
                StatusRuntimeException.class, () -> stub(VALID_TOKEN).export(request()));
        assertEquals(io.grpc.Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        verify(substrateWriter, never()).enqueue(anyString(), any());
    }

    @Test
    void exceededSpanQuota_rejectedResourceExhausted_andNeverTouchesSubstrate() {
        // gRPC parity with the HTTP path's 402: a cap that will not lift until the period rolls must not
        // surface as the UNKNOWN an unhandled exception produces, or as an OK carrying partial_success.
        when(bearerAuth.authenticate(eq("Bearer " + VALID_TOKEN))).thenReturn(Optional.of(projectToken("proj-grpc")));
        quotaFailure = new EvalsException(CapabilityError.QUOTA_EXCEEDED, "ingested_spans_monthly", 9130L, 0L);

        StatusRuntimeException ex = assertThrows(
                StatusRuntimeException.class, () -> stub(VALID_TOKEN).export(request()));

        assertEquals(io.grpc.Status.Code.RESOURCE_EXHAUSTED, ex.getStatus().getCode());
        verify(substrateWriter, never()).enqueue(anyString(), any());
    }
}
