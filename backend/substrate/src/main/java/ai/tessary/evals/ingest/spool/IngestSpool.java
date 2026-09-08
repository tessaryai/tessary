// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.spool;

import ai.tessary.evals.ingest.RawEntry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The buffer between the ingest edge and the substrate write (#984). The edge {@link #append}s a
 * decoded batch and answers the producer from the verdict; a drainer {@link #claim}s batches, writes
 * them, and {@link #ack}s or {@link #nack}s each one. {@link InProcessSpool} is the default (bounded by
 * bytes, in memory, a restart loses what is queued); the Kafka-API spool (#1299) is the opt-in (a
 * persisted publish before the 200, nothing lost on restart). Both share the drain.
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

    /** What an implementation needs back to settle a claim: a broker offset, a queue handle, nothing. */
    interface Receipt {}

    /** A batch handed to a drainer, to be acked or nacked exactly once. */
    record Claimed(
            String projectId,
            List<RawEntry> entries,
            long bytes,
            long enqueuedAtNanos,
            @Nullable Receipt receipt) {}

    /**
     * A point-in-time view for the health indicator and the throughput line. {@code deadLettered} is
     * batches that exhausted their write retries: parked on a dead-letter topic by a durable spool,
     * dropped and counted by the in-process one.
     */
    record Stats(
            String mode, boolean durable, long depth, long bytes, long maxBytes, long oldestAgeMs, long deadLettered) {}

    /**
     * Take a batch. Non-blocking for the in-process spool; a durable spool blocks for the broker's
     * acknowledgement, bounded by its publish timeout, because that acknowledgement is what a 200 means.
     */
    Admission append(String projectId, List<RawEntry> entries);

    /**
     * The next batch, or empty when none arrived within {@code wait}. Batches of one project may be
     * claimed by different drainers in any order; a caller that needs per-project serialisation
     * arranges it (the Kafka spool keys by project so one partition, hence one consumer, holds a project).
     */
    Optional<Claimed> claim(Duration wait) throws InterruptedException;

    void ack(Claimed claimed);

    /** The batch could not be written after every retry; the spool decides whether it is kept anywhere. */
    void nack(Claimed claimed);

    Stats stats();

    /**
     * How full the admission budget is, 0 to 1, read by the ingest edge on EVERY push before it decodes
     * the body (the pre-decode gate that keeps an OutOfMemoryError upstream of the shed). An
     * implementation with no in-process budget answers 0, and says so in its own Javadoc.
     */
    double pressure();

    /**
     * How many drainers may claim from this spool at once.
     *
     * <p>The default is 1, and the bar for answering more is the spool's own ordering guarantee: two
     * drainers writing batches of the SAME project race on the same trace rows. {@link InProcessSpool}
     * hands out claims in arrival order with nothing keeping a project on one claimant, so it takes the
     * default; the Kafka spool keys its messages by project, which pins a project to one partition and
     * so to one consumer in the group, and answers its consumer count.
     *
     * <p>It is the spool that answers rather than the configuration because the two can disagree: the
     * bean is chosen from the raw {@code evals.ingest.spool.mode} property by {@code @ConditionalOnProperty},
     * while {@code IngestSpoolProperties} answers from a normalised copy of the same string. Asking the
     * instance that was actually wired removes the second reading, so the count cannot describe a spool
     * other than the one running.
     */
    default int drainers() {
        return 1;
    }

    /**
     * Why the spool cannot currently accept, when it cannot: a durable spool whose broker is not
     * answering. Empty for a spool that has no remote dependency. The health contributor turns it
     * into DOWN with the message as a detail.
     */
    default Optional<String> unavailable() {
        return Optional.empty();
    }
}
