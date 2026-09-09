// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the async substrate write path, bound from {@code tessary.ingest.substrate.*}.
 * Governs {@code ingest/substrate/SubstrateWriter}: the bounded hand-off queue in front of
 * {@code SpanBatchWriter} and its at-least-once retry policy.
 *
 * <p>Distinct from {@link IngestProperties} (media-resolver bounds) by concern, mirroring the
 * per-prefix split used by {@code ObserverProperties}. Defaults live here in code (no yaml entries
 * needed), like {@link IngestProperties}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.ingest.substrate")
public class SubstrateProperties {

    /**
     * The queue's real ceiling: total payload bytes held across every queued batch. Admission reserves a
     * batch's measured size before it is enqueued and releases exactly that reservation after the drain,
     * so the accounting cannot drift from what is actually retained.
     *
     * <p><b>Measured, not estimated.</b> Jaeger's byte-bounded queue derives an average span size and
     * multiplies, and its own issue #2715 records that the average under-counts real retained heap. The
     * Datadog agent accumulates each payload's actual size instead
     * ({@code forwarder_retry_queue_payloads_max_size}, {@code currentMemSizeInBytes}); that is the model
     * here. The OpenTelemetry Collector reached the same place from the other direction — its sending
     * queue took a pluggable {@code sizer}, and {@code bytes} was added because someone filed exactly this
     * bug against the batch count.
     *
     * <p>What is counted is <em>payload</em> bytes — the character content of the entries plus a fixed
     * per-entry allowance for the object graph around it. That is a proxy for retained heap, not a
     * measurement of it, which is why the default leaves generous headroom: 64 MiB against the ~480 MB
     * heap a 2 GB container gets by default. Bounding the queue is also necessary rather than sufficient
     * — Collector issue #15747 documents a collector reaching ~200 GB RSS <em>with</em> byte sizing,
     * because memory grew after dequeue rather than before it.
     */
    private long queueMaxBytes = 64L * 1024 * 1024;

    /**
     * Fraction of {@link #queueMaxBytes} above which the OTLP receiver refuses a push <em>before</em>
     * decoding its body, answering {@code 503} + {@code Retry-After}.
     *
     * <p>The shed above is the last line, and by the time it fires the batch has already been decoded
     * into the heap it was meant to protect — which is how a full queue still ended in an
     * {@code OutOfMemoryError} raised inside protobuf parsing. The OpenTelemetry Collector's answer is a
     * memory-limiter middleware that rejects at the transport before deserialization, and Phoenix gates
     * its OTLP routes the same way, on a queue-full check evaluated as a request dependency. Both return
     * the retryable status rather than dropping.
     *
     * <p>Set to {@code 0} (or {@code >= 1}) to disable the gate and rely on the shed alone.
     */
    private double refuseAboveQueueFraction = 0.8;

    /**
     * Write attempts per batch (1 = no retry). Retries are safe — the whole write path is
     * idempotent (natural keys + {@code ON CONFLICT DO NOTHING}), so a replay of a
     * partially-written batch only fills in the missing rows.
     */
    private int maxAttempts = 3;

    /** Backoff between write attempts, in milliseconds (linear: attempt N waits N * backoff). */
    private long retryBackoffMs = 250;

    /**
     * Rows per pass for the two v2 span resolvers (ancestry and correlation).
     *
     * <p>Both are driven by partial indexes that stay near-empty by construction — a span leaves its index
     * as soon as it resolves, and the ones that can never resolve are given a terminal state so they leave
     * too. The limit therefore bounds one pass, not the backlog: a burst simply takes a few more ticks.
     */
    private int resolverBatchSize = 500;

    /**
     * Interval between resolver passes, in milliseconds. Bound as a plain property (not read through this
     * class) by the {@code @Scheduled} annotations, which need a literal placeholder.
     */
    private long resolverIntervalMs = 1000;

    /**
     * Ops kill switch for the two span resolvers. Read by their {@code @ConditionalOnProperty}, so the
     * beans do not exist when it is false; declared here so the key is bound, documented and typed rather
     * than a bare string in an annotation.
     */
    private boolean resolversEnabled = true;

    public long getQueueMaxBytes() {
        return queueMaxBytes;
    }

    public void setQueueMaxBytes(long v) {
        this.queueMaxBytes = v;
    }

    public double getRefuseAboveQueueFraction() {
        return refuseAboveQueueFraction;
    }

    public void setRefuseAboveQueueFraction(double v) {
        this.refuseAboveQueueFraction = v;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int v) {
        this.maxAttempts = v;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long v) {
        this.retryBackoffMs = v;
    }

    public int getResolverBatchSize() {
        return resolverBatchSize;
    }

    public void setResolverBatchSize(int v) {
        this.resolverBatchSize = v;
    }

    public long getResolverIntervalMs() {
        return resolverIntervalMs;
    }

    public void setResolverIntervalMs(long v) {
        this.resolverIntervalMs = v;
    }

    public boolean isResolversEnabled() {
        return resolversEnabled;
    }

    public void setResolversEnabled(boolean v) {
        this.resolversEnabled = v;
    }

    /**
     * Ops kill switch for the trace rollup worker and its reaper. Read by their
     * {@code @ConditionalOnProperty}, so the beans do not exist when it is false.
     *
     * <p>Switching it off stops every trace's counters advancing — {@code is_settled} stays false and the
     * list surfaces keep showing whatever the last rollup wrote. Nothing is lost by it: the deadlines stay
     * armed on the rows, so turning it back on drains the backlog. It is for an operator who needs the
     * connections back, not for a rollout.
     */
    private boolean rollupEnabled = true;

    /**
     * Interval between rollup worker ticks, in milliseconds. Bound as a plain property (not read through
     * this class) by the {@code @Scheduled} annotation, which needs a literal placeholder.
     */
    private long rollupIntervalMs = 1000;

    /**
     * Traces claimed per round, the spec's {@code LIMIT 500} (§7.3). It bounds one round, not the backlog:
     * a round that fills its limit is followed immediately by another.
     */
    private int rollupClaimLimit = 500;

    /** Interval between reaper sweeps, in milliseconds. A {@code @Scheduled} placeholder, as above. */
    private long rollupReapIntervalMs = 60_000;

    /**
     * How long a trace may sit claimed-but-unwritten before the reaper decides nobody is coming back for it
     * (spec §7.4's {@code interval '5 minutes'}).
     *
     * <p>Too short only costs a redundant recompute — every rollup is a replacement, so re-arming a healthy
     * in-flight claim cannot corrupt anything. Too long leaves a crashed worker's traces frozen with stale
     * counters and {@code is_settled = false} for that long, which is the failure this sweep exists for.
     */
    private int rollupReapGraceSeconds = 300;

    /**
     * How far past its deadline the oldest armed trace may fall before the sweep logs ERROR, in
     * milliseconds. Modelled on the grading-spend cursor alarm: a rollup queue that stops draining is
     * invisible from the read surfaces, which keep serving the last numbers the worker wrote as though they
     * were current.
     */
    private long rollupStaleAfterMs = 60_000;

    public boolean isRollupEnabled() {
        return rollupEnabled;
    }

    public void setRollupEnabled(boolean v) {
        this.rollupEnabled = v;
    }

    public long getRollupIntervalMs() {
        return rollupIntervalMs;
    }

    public void setRollupIntervalMs(long v) {
        this.rollupIntervalMs = v;
    }

    public int getRollupClaimLimit() {
        return rollupClaimLimit;
    }

    public void setRollupClaimLimit(int v) {
        this.rollupClaimLimit = v;
    }

    public long getRollupReapIntervalMs() {
        return rollupReapIntervalMs;
    }

    public void setRollupReapIntervalMs(long v) {
        this.rollupReapIntervalMs = v;
    }

    public int getRollupReapGraceSeconds() {
        return rollupReapGraceSeconds;
    }

    public void setRollupReapGraceSeconds(int v) {
        this.rollupReapGraceSeconds = v;
    }

    public long getRollupStaleAfterMs() {
        return rollupStaleAfterMs;
    }

    public void setRollupStaleAfterMs(long v) {
        this.rollupStaleAfterMs = v;
    }
}
