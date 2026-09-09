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
 * The transport-agnostic core of the OTLP receiver: map a decoded {@link ExportTraceServiceRequest} to a
 * canonical {@link RawEntry} batch, apply the defensive span-count clamp, tee onto the agent-native
 * substrate via the {@code SubstrateWriter.enqueue} seam, and build the OTLP
 * {@link ExportTraceServiceResponse} (with {@code partial_success} when spans were clamped).
 *
 * <p><b>The plan's span quota is enforced here</b>, before any mapping work, through {@link IngestQuotaGate}.
 * This is the only caller of {@code SubstrateWriter.enqueue}, so gating it gates every span that reaches the
 * substrate — and gating it in the shared core rather than in each transport is what stops a push refused
 * over HTTP from being accepted over gRPC.
 *
 * <p><b>Buffer pressure is checked first, quota second — cheap infra guard before billing lookup.</b>
 * {@link #shouldRefuse()} answers from an in-memory queue-fill fraction and costs nothing, so transports call
 * it before they even decode the request body. {@link IngestQuotaGate#requireIngestWithinQuota} costs a
 * (10s-cached) usage read, so it only runs here, on an already-decoded, already-admitted request, right
 * before the mapping work it would otherwise waste. The two answer different questions and fail differently:
 * a shed is transient backpressure signaled by {@link IngestOutcome#accepted()} (retry soon), while an
 * exceeded quota is a standing billing decision that {@link IngestQuotaGate} raises as
 * {@code TessaryException(QUOTA_EXCEEDED)} — a thrown fault reads more honestly than a third state wedged into
 * {@code IngestOutcome}, and it reuses the same catalog-driven HTTP 402 the rest of the plan module produces.
 *
 * <p>Both transports call this so there is genuinely <em>one</em> mapping path: the HTTP receiver
 * ({@code OtlpTraceController}) after it has decoded + size-checked the protobuf body, and the gRPC
 * receiver ({@code OtlpGrpcTraceService}) which gets the message already decoded from the stub. The
 * persisted output — sessions/turns/observations, never flat spans — is therefore identical regardless of
 * how a span arrived. Transport-specific concerns (HTTP body framing/size bound, gRPC auth metadata) stay
 * in the callers; this method owns only the shared mapping → clamp → enqueue → response.
 *
 * <p><b>Non-blocking.</b> {@code enqueue} is an O(1) offer (shed-not-block); this method does no I/O and
 * returns immediately. It does not enforce the transport gate or auth — those live at each transport's edge
 * (a request only reaches here once admitted).
 *
 * <p><b>A shed batch is not a success.</b> {@code enqueue} reports whether the batch was taken, and this
 * method passes that verdict up in {@link IngestOutcome} rather than swallowing it. The transports turn a
 * refusal into their protocol's retryable error (HTTP 503 / gRPC {@code UNAVAILABLE}), which is the
 * only reason the idempotent write path ever gets a second chance at those spans.
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
     * Whether the write buffer is too full to take another push — asked BEFORE the request body is
     * decoded, so the transport can refuse at the edge.
     *
     * <p><b>This is the one check that addresses the failure it exists for.</b> The shed inside
     * {@code SubstrateWriter.enqueue} is the last line, and by the time it fires the batch has already
     * been decoded into the heap the shed was protecting: on 2026-08-15 the queue was at half depth, the
     * shed counter read zero the whole time, and the {@code OutOfMemoryError} was raised inside
     * {@code ExportTraceServiceRequest} protobuf parsing — upstream of every protection this class has.
     *
     * <p>The pattern is the OpenTelemetry Collector's {@code memorylimiterextension}, which refuses in
     * transport middleware rather than in a processor, precisely because the processor sits downstream of
     * deserialization. Phoenix does the same with a request-scoped dependency whose docstring is explicit
     * that requests are "accepted or rejected before spans are deserialized". Both answer a retryable
     * status rather than dropping, and so does this: the caller turns {@code true} into
     * {@code 503 + Retry-After} (HTTP) or {@code UNAVAILABLE} (gRPC).
     */
    public boolean shouldRefuse() {
        double threshold = substrateProps.getRefuseAboveQueueFraction();
        if (threshold <= 0 || threshold >= 1) return false;
        if (substrateWriter.queueBytesUsedFraction() < threshold) return false;
        refusedBatches.incrementAndGet();
        return true;
    }

    /** Pushes refused at the edge on buffer pressure, before their body was decoded. */
    public long refusedBatches() {
        return refusedBatches.get();
    }

    /**
     * The verdict on one export request: the OTLP response the transport should send if it sends one, and
     * whether the substrate actually took the batch.
     *
     * <p>The two are separate because they answer different questions. {@code response} carries
     * {@code partial_success} — OTLP's <em>non-retryable</em> "these spans are gone, don't resend" — which is
     * right for the span-count clamp and wrong for a shed. {@code accepted == false} means the platform
     * failed to keep up, so the exporter must be told to come back, and the transport replaces the response
     * with a retryable error instead of returning it.
     */
    public record IngestOutcome(ExportTraceServiceResponse response, boolean accepted) {}

    /**
     * Map, clamp, enqueue, and build the OTLP response for one already-admitted export request.
     *
     * @param projectId the resolved project (token scope) the batch is teed under
     * @param request the decoded OTLP export request
     * @return the response (empty on full acceptance, carrying {@code partial_success} when spans were
     *     dropped by the {@code max-spans-per-request} clamp) plus whether the substrate took the batch
     * @throws ai.tessary.open.errors.TessaryException {@code 402 QUOTA_EXCEEDED} when the org has reached
     *     its ingested-span cap. The WHOLE batch is refused rather than partially accepted: {@code
     *     partial_success} means "we clamped an oversized push", and an exporter reacts to it by trimming and
     *     carrying on, which is exactly the wrong response to a cap that will not lift until the period rolls.
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

        // Bytes, not just span count. The 2026-07-31 saturation was driven by inline media — entries
        // carrying megabytes of base64 — and a count-only line hid that completely: the row count
        // looked trivial while the byte volume was three orders of magnitude larger. Whatever this
        // path costs, it scales with bytes, so bytes is what must be observable.
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
