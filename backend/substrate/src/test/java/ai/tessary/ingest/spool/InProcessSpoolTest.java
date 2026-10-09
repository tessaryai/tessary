// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.SubstrateProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The oldest-batch age the health check and the throughput line read. A batch a drainer has claimed but
 * not finished is still unprocessed: a hung substrate write with an empty queue must read as a growing age,
 * not as an idle spool, or the stalled ingest hides behind a healthy-looking backlog of zero.
 */
class InProcessSpoolTest {

    private static final long MAX_BYTES = 1_000_000;

    /** Starts well away from 0, which the spool reads as "no batch in flight". */
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private InProcessSpool spool() {
        SubstrateProperties props = new SubstrateProperties();
        props.setQueueMaxBytes(MAX_BYTES);
        return new InProcessSpool(props, nanos::get);
    }

    @Test
    void aClaimedBatchNotYetAckedAgesWithAnEmptyQueue_andAckClearsIt() throws InterruptedException {
        InProcessSpool spool = spool();
        spool.append("proj", List.of());
        IngestSpool.Claimed held = spool.claim(Duration.ZERO).orElseThrow();

        nanos.addAndGet(Duration.ofSeconds(5).toNanos());

        assertEquals(
                new IngestSpool.Stats("memory", false, 0, 0, MAX_BYTES, 5_000, 0),
                spool.stats(),
                "the queue is empty, but the held batch has waited five seconds");
        spool.ack(held);
        assertEquals(new IngestSpool.Stats("memory", false, 0, 0, MAX_BYTES, 0, 0), spool.stats(), "then idle");
    }

    @Test
    void aNackedBatchStopsAgingAndIsCountedDropped() throws InterruptedException {
        InProcessSpool spool = spool();
        spool.append("proj", List.of());
        IngestSpool.Claimed held = spool.claim(Duration.ZERO).orElseThrow();
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());

        spool.nack(held);

        assertEquals(new IngestSpool.Stats("memory", false, 0, 0, MAX_BYTES, 0, 1), spool.stats());
    }
}
