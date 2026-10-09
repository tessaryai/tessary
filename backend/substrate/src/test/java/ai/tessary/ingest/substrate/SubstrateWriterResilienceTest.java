// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.spool.InProcessSpool;
import ai.tessary.ingest.spool.IngestSpool;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
import ai.tessary.redaction.RedactionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The drainer survives a throw from redaction or from the span write.
 *
 * <p>When redaction moved to the drain side, {@code redactBatch} ran outside {@code writeWithRetry}'s catch, and
 * {@code drainLoop} had only a finally. One transient {@code DataAccessException} on a rule-cache miss would kill the
 * lone {@code substrate-writer} thread for good, silently shedding every later batch. A failing write is retried to
 * the cap, counted failed, and the drainer moves on.
 */
class SubstrateWriterResilienceTest {

    private static RawEntry entry(String id) {
        return new RawEntry(
                id,
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

        // Throws on the first batch only, like a transient DB blip.
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

        writer.enqueue("p1", List.of(entry("a"))); // blows up in redaction
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the poisoned batch must not leave the writer busy");

        writer.enqueue("p1", List.of(entry("b"))); // the drainer must still take this
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
                null);
    }

    /**
     * Admission is by measured bytes, not slots: at 512 slots, megabyte-scale batches exhausted the heap and the OOM
     * killed the drainer.
     */
    @Test
    void enqueue_shedsOnTheByteBudget_notTheBatchCount() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SubstrateProperties props = new SubstrateProperties();
        props.setQueueMaxBytes(300_000);
        SubstrateWriter writer = writerWith(props, blockingWriter(entered, release));
        try {
            // ~100 KB each, so the third does not fit, with no count bound at all.
            assertTrue(writer.enqueue("p1", List.of(sized("a", 100_000))), "first batch fits");
            assertTrue(writer.enqueue("p1", List.of(sized("b", 100_000))), "second batch fits");
            assertFalse(writer.enqueue("p1", List.of(sized("c", 100_000))), "third exceeds the byte budget");
            assertEquals(1L, writer.shedBatches(), "the refusal must be counted as a shed");
            // Wait until the drainer is inside the write; reserved bytes are held until ack.
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the drainer must have claimed the first batch");
            assertTrue(
                    writer.spoolStats().depth() <= 1, "one batch in flight, one queued: the count was never the bound");
        } finally {
            release.countDown();
        }
    }

    /**
     * A batch bigger than the whole budget is refused and counted apart; parking it would wedge the gate (the
     * Collector's {@code errSizeTooLarge}).
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
        assertEquals(0L, writer.spoolStats().bytes(), "the refusal must not have reserved anything");

        assertTrue(writer.enqueue("p1", List.of(sized("ok", 100))), "the queue must still accept normal work");
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the queue was wedged by the refused batch");
        assertEquals(1, writes.get(), "the normal batch still reached the write");
    }

    /** Every reservation is returned; a leaked budget ends with a queue refusing everything while holding nothing. */
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

        assertEquals(
                0L,
                writer.spoolStats().bytes(),
                "written, failed and dropped batches must all release their reservation");
    }

    /** Losing the drainer was silent: the queue stopped while HTTP kept answering 200. */
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

    /** An in-process spool that throws on append or on settle, like a broker-backed one. */
    private static final class FaultySpool implements IngestSpool {
        private final InProcessSpool delegate;
        private final boolean failAppend;
        private final boolean failSettle;

        FaultySpool(SubstrateProperties props, boolean failAppend, boolean failSettle) {
            this.delegate = new InProcessSpool(props);
            this.failAppend = failAppend;
            this.failSettle = failSettle;
        }

        @Override
        public Admission append(String projectId, List<RawEntry> entries) {
            if (failAppend) throw new IllegalStateException("broker unreachable");
            return delegate.append(projectId, entries);
        }

        @Override
        public Optional<Claimed> claim(Duration wait) throws InterruptedException {
            return delegate.claim(wait);
        }

        @Override
        public void ack(Claimed claimed) {
            if (failSettle) throw new IllegalStateException("commit failed");
            delegate.ack(claimed);
        }

        @Override
        public void nack(Claimed claimed) {
            if (failSettle) throw new IllegalStateException("commit failed");
            delegate.nack(claimed);
        }

        @Override
        public Stats stats() {
            return delegate.stats();
        }

        @Override
        public double pressure() {
            return delegate.pressure();
        }
    }

    private static RedactionService passthrough() {
        return new RedactionService(null, null) {
            @Override
            public List<RawEntry> redactBatch(String projectId, List<RawEntry> entries) {
                return entries;
            }
        };
    }

    /** A spool that throws did not take the batch: the producer is told to retry, and nothing stays pending. */
    @Test
    void aSpoolThatThrowsOnAppend_isAnsweredAsAShed() throws Exception {
        SubstrateProperties props = new SubstrateProperties();
        SubstrateWriter writer = new SubstrateWriter(
                Mockito.mock(SpanBatchWriter.class), props, passthrough(), new FaultySpool(props, true, false));

        assertFalse(writer.enqueue("p1", List.of(entry("a"))), "a batch the spool refused is not accepted");
        assertEquals(1L, writer.shedBatches());
        assertTrue(writer.awaitIdle(Duration.ofSeconds(1)), "the refused batch is not left counted as pending");
    }

    /** A settlement that throws still releases the batch, and the drainer takes the next one. */
    @Test
    void aSpoolThatThrowsOnSettlement_stillReleasesTheBatch() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> writes.incrementAndGet());
        SubstrateProperties props = new SubstrateProperties();
        SubstrateWriter writer = new SubstrateWriter(spans, props, passthrough(), new FaultySpool(props, false, true));

        writer.enqueue("p1", List.of(entry("a")));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "a failed ack must not leave the batch pending forever");
        writer.enqueue("p1", List.of(entry("b")));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "the drainer survived the failed settlement");
        assertEquals(2, writes.get());
    }

    /** Stopping the drainer mid-backoff ends the batch as failed and released, instead of sleeping it out. */
    @Test
    void aStopDuringRetryBackoff_countsTheBatchFailedAndReleasesIt() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> {
            attempted.countDown();
            throw new IllegalStateException("span write failed");
        });
        SubstrateProperties props = new SubstrateProperties();
        props.setMaxAttempts(2);
        props.setRetryBackoffMs(60_000);
        SubstrateWriter writer = writerWith(props, spans);

        writer.enqueue("p1", List.of(entry("a")));
        assertTrue(attempted.await(10, TimeUnit.SECONDS), "the first attempt ran");
        writer.killDrainerForTest();

        assertTrue(writer.awaitIdle(Duration.ofSeconds(10)), "released well before the minute-long backoff ends");
        assertEquals(1L, writer.failedBatches());
    }

    /** The wait hooks report a writer that is still busy as busy, rather than timing out into a yes. */
    @Test
    void theWaitHooksReportABusyWriterAsBusy() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SubstrateWriter writer = writerWith(new SubstrateProperties(), blockingWriter(entered, release));
        try {
            writer.enqueue("p1", List.of(entry("a")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            assertFalse(writer.awaitIdle(Duration.ofMillis(50)), "a batch is still in flight");
            assertFalse(writer.awaitDrainerDeath(Duration.ofMillis(50)), "the drainer is alive");
        } finally {
            release.countDown();
        }
    }

    /** Shutdown lets the batch in hand finish and settle, then the drainer stops rather than lingering. */
    @Test
    void shutdownFinishesTheBatchInHandThenStopsTheDrainer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        SpanBatchWriter spans = Mockito.mock(SpanBatchWriter.class);
        Mockito.when(spans.write(Mockito.anyString(), Mockito.anyList())).thenAnswer(inv -> {
            entered.countDown();
            // A write in flight does not stop for the shutdown interrupt; it finishes.
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                }
            }
            return writes.incrementAndGet();
        });
        SubstrateWriter writer = writerWith(new SubstrateProperties(), spans);
        writer.enqueue("p1", List.of(entry("a")));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        writer.shutdown();
        release.countDown();

        assertTrue(writer.awaitDrainerDeath(Duration.ofSeconds(10)), "the drainer stops after shutdown");
        assertTrue(writer.awaitIdle(Duration.ofSeconds(1)), "the batch in hand was settled on the way out");
        assertEquals(1, writes.get());
    }
}
