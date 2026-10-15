// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the async substrate write path, bound from {@code tessary.ingest.substrate.*}.
 * Governs {@code ingest/substrate/SubstrateWriter}: the bounded hand-off queue in front of
 * {@code SpanBatchWriter} and its at-least-once retry policy.
 *
 * <p>Mirrors the per-prefix split used by {@code ObserverProperties}. Defaults live here in code (no
 * yaml entries needed).
 */
@ConfigurationProperties(prefix = "tessary.ingest.substrate")
public record SubstrateProperties(

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
        @DefaultValue("67108864") long queueMaxBytes,

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
        @DefaultValue("0.8") double refuseAboveQueueFraction,

        /**
         * Write attempts per batch (1 = no retry). Retries are safe — the whole write path is
         * idempotent (natural keys + {@code ON CONFLICT DO NOTHING}), so a replay of a
         * partially-written batch only fills in the missing rows.
         */
        @DefaultValue("3") int maxAttempts,

        /** Backoff between write attempts, in milliseconds (linear: attempt N waits N * backoff). */
        @DefaultValue("250") long retryBackoffMs,

        /**
         * Rows per pass for the two v2 span resolvers (ancestry and correlation).
         *
         * <p>Both are driven by partial indexes that stay near-empty by construction — a span leaves its index
         * as soon as it resolves, and the ones that can never resolve are given a terminal state so they leave
         * too. The limit therefore bounds one pass, not the backlog: a burst simply takes a few more ticks.
         */
        @DefaultValue("500") int resolverBatchSize,

        /**
         * Upper bound on one resolver statement, in seconds. A healthy pass takes milliseconds; the bound is
         * for the one that does not, which otherwise holds its row locks and the resolver's scheduler thread
         * for as long as it runs. A cancelled pass changes nothing and the next tick retries.
         */
        @DefaultValue("30") int resolverStatementTimeoutSeconds,

        /**
         * Traces claimed per round, the spec's {@code LIMIT 500} (§7.3). It bounds one round, not the backlog:
         * a round that fills its limit is followed immediately by another.
         */
        @DefaultValue("500") int rollupClaimLimit,

        /**
         * How long a trace may sit claimed-but-unwritten before the reaper decides nobody is coming back for it
         * (spec §7.4's {@code interval '5 minutes'}).
         *
         * <p>Too short only costs a redundant recompute — every rollup is a replacement, so re-arming a healthy
         * in-flight claim cannot corrupt anything. Too long leaves a crashed worker's traces frozen with stale
         * counters and {@code is_settled = false} for that long, which is the failure this sweep exists for.
         */
        @DefaultValue("300") int rollupReapGraceSeconds,

        /**
         * How far past its deadline the oldest armed trace may fall before the sweep logs ERROR, in
         * milliseconds. Modelled on the grading-spend cursor alarm: a rollup queue that stops draining is
         * invisible from the read surfaces, which keep serving the last numbers the worker wrote as though they
         * were current.
         */
        @DefaultValue("60000") long rollupStaleAfterMs) {}
