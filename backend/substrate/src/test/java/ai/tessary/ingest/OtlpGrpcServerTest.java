// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.tessary.auth.BearerTokenAuthenticator;
import ai.tessary.config.OtlpReceiverProperties;
import ai.tessary.config.OtlpReceiverProperties.Transport;
import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.otlp.OtlpGrpcAuthInterceptor;
import ai.tessary.ingest.otlp.OtlpGrpcTraceService;
import ai.tessary.ingest.otlp.OtlpIngestService;
import ai.tessary.ingest.otlp.OtlpSpanMapper;
import ai.tessary.ingest.substrate.SubstrateWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the OTLP/gRPC binding gate: the {@link OtlpGrpcServerConfig.OtlpGrpcServer} {@link
 * org.springframework.context.SmartLifecycle} must only auto-start (and only actually bind port
 * {@code grpcPort}) when the receiver's {@code transport} includes gRPC. HTTP ingest is always on; gRPC
 * opens a separate inbound socket, so a default (HTTP-only) deployment must never bind it. The end-to-end
 * {@code OtlpGrpcTraceServiceTest} boots the handler directly on an ephemeral port and bypasses this gate,
 * so a regression that dropped the {@code includesGrpc()} term would otherwise ship green.
 */
class OtlpGrpcServerTest {

    private OtlpGrpcServerConfig.OtlpGrpcServer server(Transport transport) {
        return server(transport, 0); // ephemeral — only the gRPC-bearing transports actually bind
    }

    private OtlpGrpcServerConfig.OtlpGrpcServer server(Transport transport, int port) {
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        props.setTransport(transport);
        props.setGrpcPort(port);
        // Real collaborators: NettyServerBuilder.addService() binds the generated service descriptor, which a
        // plain mock cannot supply. The gate test never sends a request, so the substrate/auth are inert.
        OtlpSpanMapper mapper = new OtlpSpanMapper(new ObjectMapper());
        OtlpIngestService ingest = new OtlpIngestService(
                props, new SubstrateProperties(), mock(SubstrateWriter.class), mapper, projectId -> {});
        OtlpGrpcTraceService traceService = new OtlpGrpcTraceService(ingest);
        OtlpGrpcAuthInterceptor authInterceptor = new OtlpGrpcAuthInterceptor(mock(BearerTokenAuthenticator.class));
        return new OtlpGrpcServerConfig.OtlpGrpcServer(props, traceService, authInterceptor);
    }

    @Test
    void httpOnly_doesNotAutoStartOrBind() {
        OtlpGrpcServerConfig.OtlpGrpcServer s =
                server(Transport.HTTP); // default transport — HTTP front door only (always on)
        assertFalse(s.isAutoStartup(), "an HTTP-only transport must not auto-start the gRPC server");
        s.start();
        assertFalse(s.isRunning(), "start() must be a no-op when the transport excludes gRPC");
    }

    @ParameterizedTest
    @EnumSource(
            value = Transport.class,
            names = {"GRPC", "BOTH"})
    void grpc_autoStartsAndBinds(Transport transport) {
        OtlpGrpcServerConfig.OtlpGrpcServer s = server(transport);
        assertTrue(s.isAutoStartup(), "a transport including gRPC must auto-start the gRPC server");
        try {
            s.start();
            assertTrue(s.isRunning(), "start() must bind the gRPC server when the transport includes gRPC");
        } finally {
            s.stop();
        }
    }

    /** A port someone else holds fails startup loudly, rather than the app running with no gRPC receiver. */
    @Test
    void aPortAlreadyInUse_failsStartupAndLeavesNothingRunning() throws java.io.IOException {
        try (java.net.ServerSocket taken = new java.net.ServerSocket(0)) {
            OtlpGrpcServerConfig.OtlpGrpcServer s = server(Transport.GRPC, taken.getLocalPort());

            org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class, s::start);
            assertFalse(s.isRunning());
        }
    }

    /** A receiver on a known free port whose one export call hangs inside the write until released. */
    private static final class HungCall implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final OtlpGrpcServerConfig.OtlpGrpcServer server;
        final io.grpc.ManagedChannel channel;
        final com.google.common.util.concurrent.ListenableFuture<
                        io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse>
                call;

        HungCall() throws Exception {
            int port;
            try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            SubstrateWriter writer = mock(SubstrateWriter.class);
            org.mockito.Mockito.when(writer.enqueue(
                            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                    .thenAnswer(inv -> {
                        entered.countDown();
                        release.await();
                        return true;
                    });
            BearerTokenAuthenticator auth = mock(BearerTokenAuthenticator.class);
            org.mockito.Mockito.when(auth.authenticate(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(java.util.Optional.of(
                            new ai.tessary.auth.TenantContext("u", null, "org", "proj", "member", "tok")));
            OtlpReceiverProperties props = new OtlpReceiverProperties();
            props.setTransport(Transport.GRPC);
            props.setGrpcPort(port);
            server = new OtlpGrpcServerConfig.OtlpGrpcServer(
                    props,
                    new OtlpGrpcTraceService(new OtlpIngestService(
                            props, new SubstrateProperties(), writer, new OtlpSpanMapper(new ObjectMapper()), p -> {})),
                    new OtlpGrpcAuthInterceptor(auth));
            server.start();

            channel = io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress("localhost", port)
                    .usePlaintext()
                    .build();
            io.grpc.Metadata md = new io.grpc.Metadata();
            md.put(io.grpc.Metadata.Key.of("authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "Bearer tok");
            call = io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc.newFutureStub(
                            io.grpc.ClientInterceptors.intercept(
                                    channel, io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(md)))
                    .export(io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest.getDefaultInstance());
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the export call reached the write and is hanging there");
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            server.stop();
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** A stop interrupted while a call drains hands the interrupt back and still tears the server down. */
    @Test
    void anInterruptedStop_stillStopsAndKeepsTheInterrupt() throws Exception {
        try (HungCall hung = new HungCall()) {
            Thread.currentThread().interrupt();
            hung.server.stop();

            assertTrue(Thread.interrupted(), "the caller's interrupt survives the stop");
            assertFalse(hung.server.isRunning());
        }
    }

    /** A call that never finishes is cut off once the grace period ends, so shutdown cannot hang on it. */
    @Test
    void aStopWithACallThatNeverFinishes_forcesTheCallDownAfterTheGracePeriod() throws Exception {
        try (HungCall hung = new HungCall()) {
            hung.server.stop();

            assertFalse(hung.server.isRunning());
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class, () -> hung.call.get(5, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertInstanceOf(
                    io.grpc.StatusRuntimeException.class,
                    failure.getCause(),
                    "the hung call was cancelled by the forced shutdown");
        }
    }
}
