// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the live OTLP/HTTP trace receiver, bound from {@code evals.ingest.otlp.*}.
 * Governs {@code ingest/otlp/OtlpTraceController}: the push front door that decodes an OTLP
 * {@code ExportTraceServiceRequest} and tees it onto the agent-native substrate via the existing
 * async write path ({@code SubstrateWriter} → {@code SpanBatchWriter}).
 *
 * <p>Mirrors the per-prefix split used by {@link SubstrateProperties} (the receiver feeds it);
 * defaults live here in code (no yaml entries needed), like {@link IngestProperties}.
 *
 * <p><b>Always on.</b> OTLP ingest is a core feature, not an opt-in: the HTTP {@code POST /v1/traces}
 * front door always serves. It is not an open endpoint — every push must carry a valid write-scoped
 * bearer token (see {@code OtlpTraceController}), so "default off" was never a security requirement.
 *
 * <p><b>Transport selection.</b> {@link #getTransport() transport} (default {@link Transport#HTTP})
 * chooses which receive transport(s) come up. gRPC ({@code TraceService/Export} on {@link #getGrpcPort()})
 * binds a <em>separate inbound socket</em>, so it stays opt-in via this knob; both transports tee
 * into the <em>same</em> substrate path, so persisted output is identical regardless of how a span arrives.
 */
@Component
@ConfigurationProperties(prefix = "evals.ingest.otlp")
public class OtlpReceiverProperties {

    /**
     * Which OTLP receive transport(s) to start. gRPC is the OTLP default for several SDKs/Collectors;
     * {@link Transport#GRPC} / {@link Transport#BOTH} lets a stock gRPC-default exporter push with no
     * protocol override. Default {@link Transport#HTTP}: the HTTP front door is always served, while
     * gRPC binds a new inbound socket (and its port exposure) only on a conscious opt-in to this knob.
     */
    private Transport transport = Transport.HTTP;

    /**
     * TCP port the gRPC {@code TraceService/Export} server binds when {@link #transport} includes gRPC.
     * Default {@code 4317} — the OTLP/gRPC well-known port (HTTP/protobuf uses 4318). Only consulted when
     * the gRPC transport is active; with {@link Transport#HTTP} the server never starts and this is unused.
     * Not range-validated here: an invalid (negative / already-bound) port fails fast at startup when the
     * server binds, surfacing as an {@code UncheckedIOException} from {@code OtlpGrpcServer.start}.
     */
    private int grpcPort = 4317;

    /**
     * Maximum spans accepted per {@code ExportTraceServiceRequest}. A defensive bound on a single
     * push — the per-push row cap now lives here (OTLP is the only ingest front door); spans beyond it are dropped with a
     * partial-success count so a misbehaving exporter cannot enqueue an unbounded batch. The async
     * buffer ({@code SubstrateWriter}) applies its own shed-not-block backpressure downstream.
     */
    private int maxSpansPerRequest = 2000;

    /**
     * Maximum decoded request-body size in bytes. A defensive bound on this inbound surface: the
     * {@code application/x-protobuf} body is buffered into a {@code byte[]} before decode, so a
     * hostile oversized push would otherwise materialize unbounded before {@code maxSpansPerRequest}
     * (which bounds enqueued spans, not the buffered/parsed body) can apply. Checked before
     * {@code parseFrom}, so it caps the additional peak from protobuf materialization too.
     * {@code <= 0} disables the guard.
     *
     * <p><b>Was 32 MiB, which is too much of a heap to hand one request.</b> The guard was in the right
     * place and the number was the problem: against the ~480 MB heap a 2 GB container gets, one push
     * could claim 7% of it before decode, and the servlet container runs many handler threads at once.
     * Batches of 22-24 MB sailed under the old ceiling and the heap died inside protobuf parsing. 20 MiB
     * matches the OpenTelemetry Collector's {@code confighttp} default; the OTLP spec suggests 64 MiB,
     * but that assumes a collector sized for it.
     *
     * <p>A static cap is also only half the answer, because the failure was caused by data already
     * decoded rather than by any single oversized push. The other half is
     * {@code SubstrateProperties.refuseAboveQueueFraction}, which refuses on live buffer pressure before
     * the body is decoded at all.
     */
    private int maxBodyBytes = 20 * 1024 * 1024;

    /** The OTLP receive transport(s) to expose. */
    public enum Transport {
        /** HTTP/protobuf {@code POST /v1/traces} only. The default. */
        HTTP,
        /** gRPC {@code TraceService/Export} on {@link OtlpReceiverProperties#getGrpcPort()} only. */
        GRPC,
        /** Both transports, teeing into the same substrate path. */
        BOTH;

        /** Whether this selection serves the HTTP/protobuf receiver. */
        public boolean includesHttp() {
            return this == HTTP || this == BOTH;
        }

        /** Whether this selection serves the gRPC receiver. */
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
