// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.tessary.evals.auth.BearerTokenAuthenticator;
import ai.tessary.evals.config.OtlpReceiverProperties;
import ai.tessary.evals.config.OtlpReceiverProperties.Transport;
import ai.tessary.evals.config.SubstrateProperties;
import ai.tessary.evals.ingest.otlp.OtlpGrpcAuthInterceptor;
import ai.tessary.evals.ingest.otlp.OtlpGrpcTraceService;
import ai.tessary.evals.ingest.otlp.OtlpIngestService;
import ai.tessary.evals.ingest.otlp.OtlpSpanMapper;
import ai.tessary.evals.ingest.substrate.SubstrateWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        props.setTransport(transport);
        props.setGrpcPort(0); // ephemeral — only the gRPC-bearing transports actually bind
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

    @Test
    void grpc_autoStartsAndBinds() {
        OtlpGrpcServerConfig.OtlpGrpcServer s = server(Transport.GRPC);
        assertTrue(s.isAutoStartup(), "transport=GRPC must auto-start the gRPC server");
        try {
            s.start();
            assertTrue(s.isRunning(), "start() must bind the gRPC server when the transport includes gRPC");
        } finally {
            s.stop();
        }
    }

    @Test
    void both_autoStartsAndBinds() {
        OtlpGrpcServerConfig.OtlpGrpcServer s = server(Transport.BOTH);
        assertTrue(s.isAutoStartup(), "transport=BOTH must auto-start the gRPC server");
        try {
            s.start();
            assertTrue(s.isRunning(), "start() must bind the gRPC server when the transport includes gRPC");
        } finally {
            s.stop();
        }
    }
}
