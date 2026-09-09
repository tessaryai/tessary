// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate;

import ai.tessary.ingest.otlp.OtlpIngestService;
import ai.tessary.ingest.spool.IngestSpool;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
import ai.tessary.ingest.substrate.v2.SpanLateness;
import ai.tessary.ingest.substrate.v2.TraceRollupMetrics;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ingest heartbeat: one structured line a minute carrying what the write path did since the last one.
 *
 * <p><b>Why a line rather than a meter.</b> {@link SubstrateWriter} has kept these counters since it was
 * written and nothing has ever read them — they are process-local {@code AtomicLong}s with getters used by
 * one integration test. Micrometer would be the natural home, but {@code micrometer-core} arrives with
 * {@code spring-boot-starter-actuator}, which only the {@code app} module declares, and pulling it down to
 * {@code substrate} to instrument one class inverts a module boundary for a graph. Loki already receives
 * every structured field as a queryable key-value pair — that is how segment H's
 * {@code llm.spend.daily} answers a cross-org question with nothing built — so the dashboard is a LogQL
 * query and this is the thing it queries.
 *
 * <p><b>Deltas, not totals.</b> Counters that only ever rise make "is it shedding <em>now</em>" a
 * subtraction the reader has to do in their head at 3am. The line carries the interval's own numbers and
 * the queue depth at the instant it was taken, so a panel is a plot of the field.
 *
 * <p>Silent when nothing moved: a project that ingests nothing overnight should not produce 480 identical
 * lines, and "no line" is unambiguous because the depth fields are only interesting when there is traffic.
 */
@Component
public class IngestThroughputReporter {

    private static final Logger log = LoggerFactory.getLogger(IngestThroughputReporter.class);

    private final SubstrateWriter writer;
    private final SpanBatchWriter spans;
    private final SpanLateness lateness;
    private final TraceRollupMetrics rollup;
    private final OtlpIngestService ingest;

    private long lastRefused;
    private long lastOversize;
    private long lastUnparseableArgs;
    private long lastUnparseableResults;
    private long lastEnqueued;
    private long lastShed;
    private long lastFailed;
    private long lastWritten;
    private long lastDropped;
    private long lastRollupClaimed;
    private long lastRollupRecomputed;
    private long lastRollupSettled;
    private long lastRollupFailed;
    private long lastRollupRearmed;

    public IngestThroughputReporter(
            SubstrateWriter writer,
            SpanBatchWriter spans,
            SpanLateness lateness,
            TraceRollupMetrics rollup,
            OtlpIngestService ingest) {
        this.writer = writer;
        this.spans = spans;
        this.lateness = lateness;
        this.rollup = rollup;
        this.ingest = ingest;
    }

    @Scheduled(fixedDelayString = "${tessary.ingest.substrate.report-ms:60000}", initialDelayString = "60000")
    public void report() {
        // BEFORE the quiet-interval early return below, and that ordering is the whole point: a dead
        // drainer produces exactly the silence that return is looking for, so a supervisor placed after it
        // would run precisely never in the one case it exists for.
        writer.ensureDrainerAlive();

        long enqueued = writer.enqueuedBatches();
        long shed = writer.shedBatches();
        long failed = writer.failedBatches();
        long written = spans.writtenSpans();
        long dropped = spans.droppedSpans();
        long refused = ingest.refusedBatches();
        long oversize = writer.oversizeBatches();
        long unparseableArgs = spans.unparseableToolArgs();
        long unparseableResults = spans.unparseableToolResults();

        long rollupClaimed = rollup.claimedTraces();
        long rollupRecomputed = rollup.recomputedTraces();
        long rollupSettled = rollup.settledTraces();
        long rollupFailed = rollup.failedTraces();
        long rollupRearmed = rollup.rearmedTraces();

        long dEnqueued = enqueued - lastEnqueued;
        long dShed = shed - lastShed;
        long dFailed = failed - lastFailed;
        long dWritten = written - lastWritten;
        long dDropped = dropped - lastDropped;
        long dRollupClaimed = rollupClaimed - lastRollupClaimed;
        long dRollupRecomputed = rollupRecomputed - lastRollupRecomputed;
        long dRollupSettled = rollupSettled - lastRollupSettled;
        long dRollupFailed = rollupFailed - lastRollupFailed;
        long dRollupRearmed = rollupRearmed - lastRollupRearmed;
        long dRefused = refused - lastRefused;
        long dOversize = oversize - lastOversize;
        long dUnparseableArgs = unparseableArgs - lastUnparseableArgs;
        long dUnparseableResults = unparseableResults - lastUnparseableResults;

        lastRefused = refused;
        lastOversize = oversize;
        lastUnparseableArgs = unparseableArgs;
        lastUnparseableResults = unparseableResults;
        lastEnqueued = enqueued;
        lastShed = shed;
        lastFailed = failed;
        lastWritten = written;
        lastDropped = dropped;
        lastRollupClaimed = rollupClaimed;
        lastRollupRecomputed = rollupRecomputed;
        lastRollupSettled = rollupSettled;
        lastRollupFailed = rollupFailed;
        lastRollupRearmed = rollupRearmed;

        // Drained unconditionally, so a quiet interval cannot leave a stale tail to be counted twice into
        // the next line that does get emitted.
        long[] latenessBuckets = lateness.drain();

        if (dEnqueued == 0
                && dShed == 0
                && dFailed == 0
                && dWritten == 0
                && dRollupClaimed == 0
                && dRollupRearmed == 0) {
            return;
        }

        IngestSpool.Stats spoolStats = writer.spoolStats();
        long depth = spoolStats.depth();
        StructuredLog.Builder line = StructuredLog.info(log, Markers.OPS, "ingest.throughput")
                .field("batches", dEnqueued)
                .field("spans", dWritten)
                .field("unwritable_spans", dDropped)
                .field("shed_batches", dShed)
                .field("failed_batches", dFailed)
                .field("queue_depth", depth)
                .field("spool_mode", spoolStats.mode())
                .field("spool_oldest_age_ms", spoolStats.oldestAgeMs())
                .field("spool_dead_lettered", spoolStats.deadLettered())
                // The byte budget is the only admission bound; queue_depth above is a count for shape.
                // Reading depth alone is what made the last incident look survivable right up to the OOM:
                // 256 of 512 batches sounds like half the headroom left, and the bytes behind them were
                // already most of the heap.
                .field("queue_bytes", spoolStats.bytes())
                .field("queue_max_bytes", spoolStats.maxBytes())
                .field("refused_batches", dRefused)
                .field("oversize_batches", dOversize)
                // Steady state is alive=true, restarts=0. Anything else means the one thread that writes
                // spans to Postgres died — the failure that stopped ingest for good while the container
                // went on reporting healthy and accepting pushes.
                .field("drainer_alive", writer.drainerAlive())
                .field("drainers_alive", writer.drainersAlive())
                .field("drainers", writer.drainerCount())
                .field("drainer_restarts", writer.drainerRestarts())
                // Content that arrived non-blank and was not JSON, so its typed column took null. Producer
                // noise is a small steady number; a step change is this platform corrupting its own
                // payloads, which is what a text-level redactor did to 48.9% of MCP tool calls.
                .field("unparseable_tool_args", dUnparseableArgs)
                .field("unparseable_tool_results", dUnparseableResults)
                // The rollup queue (spec §7.2–§7.4). `rollup_claimed` minus `rollup_settled` is spans
                // landing mid-rollup — the same finding the lateness buckets below report from the span's
                // side. `rollup_rearmed` is steady-state zero: anything else is a worker that died mid-claim.
                // The two gauges are as of the last reaper sweep, not this instant.
                .field("rollup_claimed", dRollupClaimed)
                .field("rollup_recomputed", dRollupRecomputed)
                .field("rollup_settled", dRollupSettled)
                .field("rollup_failed", dRollupFailed)
                .field("rollup_rearmed", dRollupRearmed)
                .field("rollup_queue_depth", rollup.queueDepth())
                .field("rollup_overdue_ms", rollup.overdueMs());
        // Span lateness (spec §7.6): how far behind its trace's last rollup each span arrived. The tail is
        // what sets the §7.4 rollup windows, and the negative bucket is a producer whose clock stepped
        // backward — the one §6.2 failure that is otherwise entirely invisible.
        String[] latenessFields = SpanLateness.fields();
        for (int i = 0; i < latenessFields.length; i++) {
            line = line.field(latenessFields[i], latenessBuckets[i]);
        }
        line.log();
    }
}
