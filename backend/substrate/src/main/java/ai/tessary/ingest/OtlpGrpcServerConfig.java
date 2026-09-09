// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.config.OtlpReceiverProperties;
import ai.tessary.ingest.otlp.OtlpGrpcAuthInterceptor;
import ai.tessary.ingest.otlp.OtlpGrpcTraceService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires + lifecycle-manages the OTLP/gRPC {@code TraceService/Export} server.
 *
 * <p>The server binds {@code tessary.ingest.otlp.grpc-port} (default 4317) <em>only</em> when the receiver's
 * {@link OtlpReceiverProperties#getTransport() transport} includes gRPC — otherwise the
 * {@link OtlpGrpcServer} {@link SmartLifecycle} is a no-op and no socket is opened. HTTP ingest is always
 * on; gRPC binds a separate inbound socket, so it stays opt-in via the transport knob (default HTTP).
 *
 * <p>Requests run on {@code Executors.newVirtualThreadPerTaskExecutor()} — the project's Loom idiom
 * (matching {@code SubstrateWriter} and the E2B sandboxes) — so a per-request handler can block freely on the
 * (non-blocking, O(1)) substrate enqueue without tying up a Netty event-loop thread. The server infra and its
 * lifecycle live here in {@code config/}; the request handler + auth live in the {@code ingest/otlp/} slice.
 */
@Configuration(proxyBeanMethods = false)
public class OtlpGrpcServerConfig {

    @Bean
    OtlpGrpcServer otlpGrpcServer(
            OtlpReceiverProperties props, OtlpGrpcTraceService traceService, OtlpGrpcAuthInterceptor authInterceptor) {
        return new OtlpGrpcServer(props, traceService, authInterceptor);
    }

    /**
     * A Spring {@link SmartLifecycle} owning the gRPC {@link Server} socket. Starting/stopping is bound to the
     * application lifecycle, but the server only actually binds when the transport includes gRPC — so a
     * default (HTTP-only) deployment never opens port 4317.
     */
    static final class OtlpGrpcServer implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(OtlpGrpcServer.class);

        private final OtlpReceiverProperties props;
        private final OtlpGrpcTraceService traceService;
        private final OtlpGrpcAuthInterceptor authInterceptor;

        /**
         * Null until {@link #start()} and again after {@link #stop()} — a lifecycle-scoped handle, not
         * an invariant. Declared {@code @Nullable} now that this class lives in a NullAway-checked
         * package; the null handling in {@code stop()} was already written for it, only the annotation
         * was missing while {@code config} went unchecked.
         */
        @Nullable
        private volatile Server server;

        OtlpGrpcServer(
                OtlpReceiverProperties props,
                OtlpGrpcTraceService traceService,
                OtlpGrpcAuthInterceptor authInterceptor) {
            this.props = props;
            this.traceService = traceService;
            this.authInterceptor = authInterceptor;
        }

        private boolean grpcEnabled() {
            return props.getTransport().includesGrpc();
        }

        @Override
        public void start() {
            if (!grpcEnabled() || server != null) return;
            int port = props.getGrpcPort();
            NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .addService(traceService)
                    .intercept(authInterceptor);
            // Share the one configured byte bound with the HTTP path (OtlpTraceController honours
            // max-body-bytes); otherwise grpc-java's 4 MiB default would silently reject 4–32 MiB
            // batches an operator sized for the HTTP receiver. <= 0 leaves grpc-java's default in place.
            int maxBodyBytes = props.getMaxBodyBytes();
            if (maxBodyBytes > 0) {
                builder.maxInboundMessageSize(maxBodyBytes);
            }
            Server built = builder.build();
            try {
                built.start();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to bind OTLP gRPC receiver on port " + port, e);
            }
            this.server = built;
            log.info("OTLP gRPC receiver listening port={}", port);
        }

        @Override
        public void stop() {
            Server running = this.server;
            if (running == null) return;
            this.server = null;
            try {
                running.shutdown();
                if (!running.awaitTermination(5, TimeUnit.SECONDS)) {
                    running.shutdownNow();
                }
            } catch (InterruptedException e) {
                running.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("OTLP gRPC receiver stopped");
        }

        @Override
        public boolean isRunning() {
            Server running = this.server;
            return running != null && !running.isShutdown();
        }

        @Override
        public boolean isAutoStartup() {
            return grpcEnabled();
        }

        /**
         * Start late / stop early relative to the rest of the context (a high phase): the inbound socket
         * should not accept traffic until the substrate write path it feeds is up.
         */
        @Override
        public int getPhase() {
            return Integer.MAX_VALUE - 100;
        }
    }
}
