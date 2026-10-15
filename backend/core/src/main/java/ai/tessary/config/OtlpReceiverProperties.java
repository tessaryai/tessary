// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Defaults live here in code. Always on, but every push needs a write-scoped bearer token. gRPC binds a separate
 * inbound socket, so it stays opt-in; both transports feed the same substrate path.
 */
@ConfigurationProperties(prefix = "tessary.ingest.otlp")
public record OtlpReceiverProperties(

        /** Default HTTP: gRPC opens a new inbound socket only on a conscious opt-in. */
        @DefaultValue("HTTP") Transport transport,

        /** The OTLP/gRPC well-known port. Not range-validated: a bad port fails fast when the server binds. */
        @DefaultValue("4317") int grpcPort,

        /** Spans past this are dropped with a partial-success count. */
        @DefaultValue("2000") int maxSpansPerRequest,

        /**
         * Checked before {@code parseFrom}, since the body is buffered whole. 20 MiB, the OTel Collector's
         * {@code confighttp} default: 32 MiB let 22-24 MB batches kill the heap in protobuf parsing. {@code <= 0}
         * disables. {@code SubstrateProperties.refuseAboveQueueFraction} is the other half.
         */
        @DefaultValue("20971520") int maxBodyBytes) {

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
}
