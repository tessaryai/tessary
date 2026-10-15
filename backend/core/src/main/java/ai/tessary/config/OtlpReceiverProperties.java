// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Defaults live here in code. Always on, but every push needs a write-scoped bearer token. gRPC binds a separate
 * inbound socket, so it stays opt-in; both transports feed the same substrate path.
 */
@Component
@ConfigurationProperties(prefix = "tessary.ingest.otlp")
public class OtlpReceiverProperties {

    /** Default HTTP: gRPC opens a new inbound socket only on a conscious opt-in. */
    private Transport transport = Transport.HTTP;

    /** The OTLP/gRPC well-known port. Not range-validated: a bad port fails fast when the server binds. */
    private int grpcPort = 4317;

    /** Spans past this are dropped with a partial-success count. */
    private int maxSpansPerRequest = 2000;

    /**
     * Checked before {@code parseFrom}, since the body is buffered whole. 20 MiB, the OTel Collector's
     * {@code confighttp} default: 32 MiB let 22-24 MB batches kill the heap in protobuf parsing. {@code <= 0} disables.
     * {@code SubstrateProperties.refuseAboveQueueFraction} is the other half.
     */
    private int maxBodyBytes = 20 * 1024 * 1024;

    public enum Transport {
        HTTP,
        GRPC,
        BOTH;

        public boolean includesHttp() {
            return this == HTTP || this == BOTH;
        }

        public boolean includesGrpc() {
            return this == GRPC || this == BOTH;
        }
    }

    public Transport getTransport() {
        return transport;
    }

    public void setTransport(Transport v) {
        this.transport = v;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(int v) {
        this.grpcPort = v;
    }

    public int getMaxSpansPerRequest() {
        return maxSpansPerRequest;
    }

    public void setMaxSpansPerRequest(int v) {
        this.maxSpansPerRequest = v;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int v) {
        this.maxBodyBytes = v;
    }
}
