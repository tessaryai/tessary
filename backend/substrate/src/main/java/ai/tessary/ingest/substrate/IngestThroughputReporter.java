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
 * One structured line a minute of write-path deltas, queried in Loki; Micrometer would require pulling actuator into
 * {@code substrate}. Silent when nothing moved.
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
        // Before the quiet-interval return: a dead drainer produces exactly that silence.
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

        // Drained unconditionally so a quiet interval cannot carry a stale tail into the next line.
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
                // The byte budget is the only admission bound; depth alone looked survivable right up to the OOM.
                .field("queue_bytes", spoolStats.bytes())
                .field("queue_max_bytes", spoolStats.maxBytes())
                .field("refused_batches", dRefused)
                .field("oversize_batches", dOversize)
                // Steady state is alive=true, restarts=0; anything else means the span writer thread died while the
                // container reported healthy.
                .field("drainer_alive", writer.drainerAlive())
                .field("drainers_alive", writer.drainersAlive())
                .field("drainers", writer.drainerCount())
                .field("drainer_restarts", writer.drainerRestarts())
                // Producer noise is small and steady; a step change means this platform is corrupting its own payloads.
                .field("unparseable_tool_args", dUnparseableArgs)
                .field("unparseable_tool_results", dUnparseableResults)
                // `rollup_claimed` minus `rollup_settled` is spans landing mid-rollup; `rollup_rearmed` is steady-state
                // zero. The gauges are as of the last reaper sweep.
                .field("rollup_claimed", dRollupClaimed)
                .field("rollup_recomputed", dRollupRecomputed)
                .field("rollup_settled", dRollupSettled)
                .field("rollup_failed", dRollupFailed)
                .field("rollup_rearmed", dRollupRearmed)
                .field("rollup_queue_depth", rollup.queueDepth())
                .field("rollup_overdue_ms", rollup.overdueMs());
        // The negative bucket is a producer whose clock stepped backward, otherwise invisible.
        String[] latenessFields = SpanLateness.fields();
        for (int i = 0; i < latenessFields.length; i++) {
            line = line.field(latenessFields[i], latenessBuckets[i]);
        }
        line.log();
    }
}
