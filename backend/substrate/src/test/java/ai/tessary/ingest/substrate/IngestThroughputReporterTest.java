// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import ai.tessary.ingest.otlp.OtlpIngestService;
import ai.tessary.ingest.spool.IngestSpool;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
import ai.tessary.ingest.substrate.v2.SpanLateness;
import ai.tessary.ingest.substrate.v2.TraceRollupMetrics;
import ai.tessary.testsupport.LogCapture;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The minute line in Loki: what moved since the last line, and nothing when nothing moved. */
@ExtendWith(MockitoExtension.class)
class IngestThroughputReporterTest {

    @Mock
    SubstrateWriter writer;

    @Mock
    SpanBatchWriter spans;

    @Mock
    OtlpIngestService ingest;

    @RegisterExtension
    final LogCapture log = LogCapture.of(IngestThroughputReporter.class);

    /**
     * The bugs are a running total logged as the interval's count, a counter wired to the wrong field, a lateness
     * bucket carried into the next line, and a line for a quiet minute.
     */
    @Test
    void eachLineCarriesTheIntervalsDeltasAndAQuietIntervalLogsNothing() {
        SpanLateness lateness = new SpanLateness();
        TraceRollupMetrics rollup = new TraceRollupMetrics();
        var reporter = new IngestThroughputReporter(writer, spans, lateness, rollup, ingest);
        when(writer.enqueuedBatches()).thenReturn(4L, 9L);
        when(writer.shedBatches()).thenReturn(1L, 3L);
        when(writer.failedBatches()).thenReturn(0L, 1L);
        when(writer.oversizeBatches()).thenReturn(1L, 12L);
        when(spans.writtenSpans()).thenReturn(40L, 100L);
        when(spans.droppedSpans()).thenReturn(2L, 6L);
        when(spans.unparseableToolArgs()).thenReturn(0L, 13L);
        when(spans.unparseableToolResults()).thenReturn(0L, 14L);
        when(ingest.refusedBatches()).thenReturn(3L, 10L);
        when(writer.spoolStats()).thenReturn(new IngestSpool.Stats("memory", false, 21, 2048, 4096, 250, 3));
        when(writer.drainerAlive()).thenReturn(true);
        when(writer.drainersAlive()).thenReturn(2);
        when(writer.drainerCount()).thenReturn(2);
        when(writer.drainerRestarts()).thenReturn(1L);

        rollup.recordPass(5, 4, 3, 1);
        rollup.recordSweep(2, 7, 900);
        lateness.record(-5);
        reporter.report();
        assertEquals(4L, fields(log.events().get(0)).get("batches"), "the first line counts from zero");

        rollup.recordPass(6, 5, 4, 2);
        rollup.recordSweep(8, 4, 1500);
        lateness.record(400);
        lateness.record(70_000);
        reporter.report();
        assertEquals(
                Map.ofEntries(
                        entry("event", "ingest.throughput"),
                        entry("batches", 5L),
                        entry("spans", 60L),
                        entry("unwritable_spans", 4L),
                        entry("shed_batches", 2L),
                        entry("failed_batches", 1L),
                        entry("queue_depth", 21L),
                        entry("spool_mode", "memory"),
                        entry("spool_oldest_age_ms", 250L),
                        entry("spool_dead_lettered", 3L),
                        entry("queue_bytes", 2048L),
                        entry("queue_max_bytes", 4096L),
                        entry("refused_batches", 7L),
                        entry("oversize_batches", 11L),
                        entry("drainer_alive", true),
                        entry("drainers_alive", 2),
                        entry("drainers", 2),
                        entry("drainer_restarts", 1L),
                        entry("unparseable_tool_args", 13L),
                        entry("unparseable_tool_results", 14L),
                        entry("rollup_claimed", 6L),
                        entry("rollup_recomputed", 5L),
                        entry("rollup_settled", 4L),
                        entry("rollup_failed", 2L),
                        entry("rollup_rearmed", 8L),
                        entry("rollup_queue_depth", 4),
                        entry("rollup_overdue_ms", 1500L),
                        entry("late_negative", 0L),
                        entry("late_lt_1s", 1L),
                        entry("late_lt_10s", 0L),
                        entry("late_lt_60s", 0L),
                        entry("late_lt_5m", 1L),
                        entry("late_ge_5m", 0L)),
                fields(log.events().get(1)));

        reporter.report();
        assertEquals(2, log.events().size(), "nothing moved, so no third line");
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value));
    }
}
