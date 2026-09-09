// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.spool.InProcessSpool;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
import ai.tessary.redaction.RedactionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The drainer must survive a throw from redaction, and it must survive a throw from the span write.
 *
 * <p>Redaction moved from {@code enqueue} (the HTTP request thread) to the drain side. That move
 * carried a hazard review caught: {@code redactBatch} was passed as an <em>argument</em> to
 * {@code writeWithRetry}, so it ran outside that method's {@code catch}, and {@code drainLoop} had
 * only a {@code finally}. {@code RedactionService.compiledFor} does a JDBC read on a cache miss, so a
 * single transient {@code DataAccessException} would escape the loop and kill the one
 * {@code substrate-writer} thread <b>permanently</b> — every later batch silently shed,
 * {@code awaitIdle} never true again, no counter, no log.
 *
 * <p>On the request thread the same throw failed one request. On a lone drainer it is unrecoverable,
 * which is why it is pinned here rather than left to reading.
 *
 * <p>The write case is the same hazard from the other direction: a batch whose write throws is retried
 * to the attempt cap and then counted as failed, and the drainer takes the next batch either way.
 */
class SubstrateWriterResilienceTest {

    private static RawEntry entry(String id) {
        return new RawEntry(
                id,
                null,
                "span",
                "in",
                "out",
                null,
                null,
                null,
                "trace-1",
                Instant.now().toString(),
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void aThrowFromRedactionDoesNotKillTheDrainer() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> {
            writes.incrementAndGet();
            return 1;
        });

        // Throws on the first batch only, exactly like a transient DB blip on a compiled-rule cache miss.
        AtomicInteger calls = new AtomicInteger();
        RedactionService flaky = new RedactionService(null, null) {
            @Override
            public List<RawEntry> redactBatch(String projectId, List<RawEntry> entries) {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("transient rule-store read failure");
                return entries;
            }
        };

        SubstrateWriter writer = new SubstrateWriter(
                spans, new SubstrateProperties(), flaky, new InProcessSpool(new SubstrateProperties()));

        writer.enqueue("p1", List.of(entry("a"))); // this one blows up in redaction
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the poisoned batch must not leave the writer busy");

        writer.enqueue("p1", List.of(entry("b"))); // the drainer must still be alive to take this
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "drainer died on the earlier throw");

        assertEquals(1, writes.get(), "the surviving batch must still reach the substrate write");
    }

    @Test
    void aThrowFromTheWriteIsRetriedCountedAndSurvived() throws Exception {
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList()))
                .thenThrow(new IllegalStateException("span write failed"));

        RedactionService passthrough = new RedactionService(null, null) {
            @Override
            public List<RawEntry> redactBatch(String projectId, List<RawEntry> entries) {
                return entries;
            }
        };
        SubstrateProperties props = new SubstrateProperties();
        props.setMaxAttempts(2);
        props.setRetryBackoffMs(1);

        SubstrateWriter writer = new SubstrateWriter(spans, props, passthrough, new InProcessSpool(props));

        writer.enqueue("p1", List.of(entry("a")));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the failed batch must not leave the writer busy");
        writer.enqueue("p1", List.of(entry("b")));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "drainer died on the write throw");

        Mockito.verify(spans, Mockito.times(4)).write(Mockito.anyString(), Mockito.anyList());
        assertEquals(2L, writer.failedBatches(), "both batches exhausted their attempts and are counted once each");
    }

    // ---- the byte budget ----

    /** A blocking writer, so batches stay queued and the byte accounting can be observed at rest. */
    private static SpanBatchWriter blockingWriter(CountDownLatch entered, CountDownLatch release) {
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        try {
            Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> {
                entered.countDown();
                release.await();
                return 1;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return spans;
    }

    private static SubstrateWriter writerWith(SubstrateProperties props, SpanBatchWriter spans) {
        RedactionService passthrough = new RedactionService(null, null) {
            @Override
            public List<RawEntry> redactBatch(String projectId, List<RawEntry> entries) {
                return entries;
            }
        };
        return new SubstrateWriter(spans, props, passthrough, new InProcessSpool(props));
    }

    /** An entry whose payload is a known size, so a test can state a budget in terms of batches. */
    private static RawEntry sized(String id, int payloadChars) {
        return new RawEntry(
                id,
                null,
                "span",
                "x".repeat(payloadChars),
                null,
                null,
                null,
                null,
                "trace-1",
                Instant.now().toString(),
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * The bound that matters. A batch is admitted on its measured size, not on a slot count — which is the
     * whole correction: at 512 slots the queue held enough megabyte-scale batches to exhaust the heap,
     * and the {@code OutOfMemoryError} killed the drainer outright.
     */
    @Test
    void enqueue_shedsOnTheByteBudget_notTheBatchCount() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SubstrateProperties props = new SubstrateProperties();
        props.setQueueMaxBytes(300_000);
        SubstrateWriter writer = writerWith(props, blockingWriter(entered, release));
        try {
            // ~100 KB each (payload + the fixed per-entry allowance), so two fit under 300 KB and the
            // third does not — with no count bound at all, which is the point.
            assertTrue(writer.enqueue("p1", List.of(sized("a", 100_000))), "first batch fits");
            assertTrue(writer.enqueue("p1", List.of(sized("b", 100_000))), "second batch fits");
            assertFalse(writer.enqueue("p1", List.of(sized("c", 100_000))), "third exceeds the byte budget");
            assertEquals(1L, writer.shedBatches(), "the refusal must be counted as a shed");
            // Depth only drops once the drainer has CLAIMED a batch, so wait for it to be inside the
            // write rather than racing it: the reserved bytes are held until ack, so nothing above
            // this line depends on the timing.
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the drainer must have claimed the first batch");
            assertTrue(writer.queueDepth() <= 1, "one batch in flight, one queued: the count was never the bound");
        } finally {
            release.countDown();
        }
    }

    /**
     * A batch bigger than the whole budget can never be satisfied by any amount of draining, so parking it
     * would hold the gate shut against every other producer. The Collector raises {@code errSizeTooLarge}
     * for exactly this; here it is refused and counted apart from an ordinary shed.
     */
    @Test
    void enqueue_refusesABatchLargerThanTheWholeBudget_withoutWedgingTheQueue() throws Exception {
        SubstrateProperties props = new SubstrateProperties();
        props.setQueueMaxBytes(50_000);
        AtomicInteger writes = new AtomicInteger();
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> {
            writes.incrementAndGet();
            return 1;
        });
        SubstrateWriter writer = writerWith(props, spans);

        assertFalse(writer.enqueue("p1", List.of(sized("huge", 200_000))), "an oversized batch must be refused");
        assertEquals(1L, writer.oversizeBatches(), "counted apart from a shed: this one never clears on its own");
        assertEquals(0L, writer.shedBatches(), "an oversize refusal is not a shed");
        assertEquals(0L, writer.queueBytes(), "the refusal must not have reserved anything");

        assertTrue(writer.enqueue("p1", List.of(sized("ok", 100))), "the queue must still accept normal work");
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the queue was wedged by the refused batch");
        assertEquals(1, writes.get(), "the normal batch still reached the write");
    }

    /**
     * Every reservation must be returned. A release that can be skipped is a leak, and a leaked byte
     * budget ends with the queue refusing everything while holding nothing — the same outage as a dead
     * drainer, reached by arithmetic instead.
     */
    @Test
    void queuedBytes_returnToZeroAfterEveryBatchDrains() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> {
            writes.incrementAndGet();
            if (writes.get() == 2) throw new IllegalStateException("this batch fails after reserving");
            return 1;
        });
        SubstrateProperties props = new SubstrateProperties();
        props.setMaxAttempts(1);
        SubstrateWriter writer = writerWith(props, spans);

        for (int i = 0; i < 5; i++) {
            assertTrue(writer.enqueue("p1", List.of(sized("e" + i, 1_000))), "batch " + i + " must be admitted");
        }
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the queue must drain");

        assertEquals(0L, writer.queueBytes(), "written, failed and dropped batches must all release their reservation");
    }

    /**
     * The supervisor. Losing the drainer is unrecoverable and, before this, silent — the queue simply
     * stopped draining while the HTTP surface went on accepting and answering 200.
     */
    @Test
    void ensureDrainerAlive_replacesADeadDrainer() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> {
            writes.incrementAndGet();
            return 1;
        });
        SubstrateWriter writer = writerWith(new SubstrateProperties(), spans);

        writer.killDrainerForTest();
        assertTrue(writer.awaitDrainerDeath(Duration.ofSeconds(10)), "the drainer should have stopped");

        writer.ensureDrainerAlive();
        assertTrue(writer.drainerAlive(), "the supervisor must have started a replacement");
        assertEquals(1L, writer.drainerRestarts(), "and counted it");

        assertTrue(writer.enqueue("p1", List.of(sized("after", 100))), "admitted");
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the replacement drainer must be draining");
        assertEquals(1, writes.get(), "work enqueued after the restart still reaches the write");
    }
}
