// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import ai.tessary.config.OtlpReceiverProperties;
import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.IngestQuotaGate;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Both transports call this, so there is one mapping path and a push refused over HTTP cannot be accepted over gRPC.
 * Buffer pressure is checked first (in-memory, before decode), quota second (a cached read, before mapping). A shed is
 * reported up in {@link IngestOutcome} so the transport answers a retryable error.
 */
@Component
public class OtlpIngestService {

    private static final Logger log = LoggerFactory.getLogger(OtlpIngestService.class);

    private final OtlpReceiverProperties props;
    private final SubstrateProperties substrateProps;
    private final SubstrateWriter substrateWriter;
    private final OtlpSpanMapper spanMapper;
    private final AtomicLong refusedBatches = new AtomicLong();
    private final IngestQuotaGate quotaGate;

    public OtlpIngestService(
            OtlpReceiverProperties props,
            SubstrateProperties substrateProps,
            SubstrateWriter substrateWriter,
            OtlpSpanMapper spanMapper,
            IngestQuotaGate quotaGate) {
        this.props = props;
        this.substrateProps = substrateProps;
        this.substrateWriter = substrateWriter;
        this.spanMapper = spanMapper;
        this.quotaGate = quotaGate;
    }

    /**
     * Asked before the body is decoded: the shed inside {@code SubstrateWriter.enqueue} fires too late, after decode,
     * and an OOM once hit during protobuf parsing with the shed counter at zero. Same pattern as the OTel Collector's
     * memory limiter.
     */
    public boolean shouldRefuse() {
        double threshold = substrateProps.getRefuseAboveQueueFraction();
        if (threshold <= 0 || threshold >= 1) return false;
        if (substrateWriter.queueBytesUsedFraction() < threshold) return false;
        refusedBatches.incrementAndGet();
        return true;
    }

    public long refusedBatches() {
        return refusedBatches.get();
    }

    /**
     * {@code partial_success} is OTLP's non-retryable "these are gone", right for the span-count clamp and wrong for a
     * shed, which the transport turns into a retryable error.
     */
    public record IngestOutcome(ExportTraceServiceResponse response, boolean accepted) {}

    /**
     * @throws ai.tessary.open.errors.TessaryException {@code 402 QUOTA_EXCEEDED}; the whole batch is refused, because
     * an exporter reacts to {@code partial_success} by trimming and carrying on
     */
    public IngestOutcome ingest(String projectId, ExportTraceServiceRequest request) {
        Instant started = Instant.now();
        // Before the mapping work: a refused batch should cost a cached quota lookup, not a full decode.
        quotaGate.requireIngestWithinQuota(projectId);
        List<RawEntry> entries = spanMapper.toRawEntries(request);

        int accepted = entries.size();
        int rejected = 0;
        int max = Math.max(0, props.getMaxSpansPerRequest());
        if (accepted > max) {
            rejected = accepted - max;
            entries = entries.subList(0, max);
            accepted = max;
        }

        // Bytes, not just span count: inline base64 media once drove a saturation that a count-only line hid
        // completely.
        long bytes = 0;
        for (RawEntry e : entries) {
            bytes += len(e.input()) + len(e.output()) + len(e.inputMessagesJson()) + len(e.outputMessagesJson());
        }
        boolean enqueued = substrateWriter.enqueue(projectId, entries);
        if (enqueued) {
            StructuredLog.info(log, Markers.OPS, "ingest.otlp.received")
                    .message(
                            "accepted %d span(s) (%d KB)%s",
                            accepted, bytes / 1024, rejected > 0 ? ", rejected " + rejected + " over the cap" : "")
                    .field("spans", accepted)
                    .field("rejected", rejected)
                    .field("bytes", bytes)
                    .durationMs(started)
                    .log();
        } else {
            StructuredLog.warn(log, Markers.OPS, "ingest.otlp.shed")
                    .message(
                            "shed %d span(s) (%d KB) — write buffer full, told the exporter to retry",
                            accepted, bytes / 1024)
                    .field("spans", accepted)
                    .field("bytes", bytes)
                    .durationMs(started)
                    .log();
        }

        ExportTraceServiceResponse.Builder response = ExportTraceServiceResponse.newBuilder();
        if (rejected > 0) {
            response.setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                    .setRejectedSpans(rejected)
                    .setErrorMessage("span batch exceeded tessary.ingest.otlp.max-spans-per-request=" + max)
                    .build());
        }
        return new IngestOutcome(response.build(), enqueued);
    }

    private static int len(@Nullable String s) {
        return s == null ? 0 : s.length();
    }
}
