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
 * The OTLP/gRPC server binds {@code tessary.ingest.otlp.grpc-port} only when the transport includes gRPC; HTTP is
 * always on. Requests run on virtual threads so a handler never ties up a Netty event loop.
 */
@Configuration(proxyBeanMethods = false)
public class OtlpGrpcServerConfig {

    @Bean
    OtlpGrpcServer otlpGrpcServer(
            OtlpReceiverProperties props, OtlpGrpcTraceService traceService, OtlpGrpcAuthInterceptor authInterceptor) {
        return new OtlpGrpcServer(props, traceService, authInterceptor);
    }

    /** Never opens port 4317 on an HTTP-only deployment. */
    static final class OtlpGrpcServer implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(OtlpGrpcServer.class);

        private final OtlpReceiverProperties props;
        private final OtlpGrpcTraceService traceService;
        private final OtlpGrpcAuthInterceptor authInterceptor;

        /** Null until {@link #start()} and again after {@link #stop()}. */
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
            return props.transport().includesGrpc();
        }

        @Override
        public void start() {
            if (!grpcEnabled() || server != null) return;
            int port = props.grpcPort();
            NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .addService(traceService)
                    .intercept(authInterceptor);
            // Shares the HTTP path's byte bound; otherwise grpc-java's 4 MiB default would reject batches sized for
            // HTTP. <= 0 keeps grpc-java's default.
            int maxBodyBytes = props.maxBodyBytes();
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

        /** A high phase: the socket must not accept traffic until the write path it feeds is up. */
        @Override
        public int getPhase() {
            return Integer.MAX_VALUE - 100;
        }
    }
}
