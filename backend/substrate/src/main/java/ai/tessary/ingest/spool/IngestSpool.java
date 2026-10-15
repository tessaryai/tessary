// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import ai.tessary.ingest.RawEntry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The buffer between the ingest edge and the substrate write. {@link InProcessSpool} is the default (bounded by bytes,
 * lost on restart); the Kafka-API spool persists before the 200.
 */
public interface IngestSpool {

    enum Admission {
        /** The batch is the spool's problem now. */
        ACCEPTED,
        /** No room; the producer is told to retry. */
        SHED,
        /** Larger than the whole budget; the producer must split. */
        OVERSIZE
    }

    interface Receipt {}

    /** Acked or nacked exactly once. */
    record Claimed(
            String projectId,
            List<RawEntry> entries,
            long bytes,
            long enqueuedAtNanos,
            @Nullable Receipt receipt) {}

    /**
     * {@code deadLettered}: batches that exhausted write retries, parked by a durable spool or dropped and counted by
     * the in-process one.
     */
    record Stats(
            String mode, boolean durable, long depth, long bytes, long maxBytes, long oldestAgeMs, long deadLettered) {}

    /** A durable spool blocks for the broker's acknowledgement, because that is what a 200 means. */
    Admission append(String projectId, List<RawEntry> entries);

    /**
     * Empty when none arrived within {@code wait}. Per-project ordering is not guaranteed; the Kafka spool keys by
     * project so one consumer holds a project.
     */
    Optional<Claimed> claim(Duration wait) throws InterruptedException;

    void ack(Claimed claimed);

    /** The batch could not be written after every retry; the spool decides whether it is kept anywhere. */
    void nack(Claimed claimed);

    Stats stats();

    /**
     * 0 to 1, read on every push before decoding: the gate that keeps an OutOfMemoryError upstream of the shed. A spool
     * with no in-process budget answers 0.
     */
    double pressure();

    /**
     * Two drainers writing the same project race on the same trace rows, so more than 1 needs a per-project ordering
     * guarantee. Asked of the wired instance so the count describes the spool actually running.
     */
    default int drainers() {
        return 1;
    }

    /** A durable spool whose broker is not answering; the health contributor reports it as DOWN. */
    default Optional<String> unavailable() {
        return Optional.empty();
    }
}
