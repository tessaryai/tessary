// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.RawEntry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default spool: an in-memory queue bounded by payload bytes. Accepted means queued; a
 * full queue sheds, and the edge answers the producer with a retryable status so a stock exporter
 * re-sends into the idempotent write path. A restart loses what is queued, which is the contract the
 * runbook publishes for this mode; the Kafka-API spool is the opt-in for "accepted means persisted".
 *
 * <p>Bytes are the only ceiling: a batch holds its entries' payloads inline and can span an ~80x size
 * range, so a count bounds nothing that matters, and the per-entry allowance in {@link #measure}
 * already caps how many batches the byte budget can hold (fewer than 140k at 64 MiB), so the queue's
 * own node chain needs no guard of its own.
 */
public final class InProcessSpool implements IngestSpool {

    private static final Logger log = LoggerFactory.getLogger(InProcessSpool.class);

    /**
     * Fixed allowance per entry for the object graph around its payload — the record itself, its field
     * references, the boxed strings' headers. 512 bytes is generous for a record of fifteen references.
     */
    private static final long ENTRY_OVERHEAD_BYTES = 512;

    private static final long METADATA_VALUE_BYTES = 32;

    private final BlockingQueue<Claimed> queue = new LinkedBlockingQueue<>();
    private final long maxQueueBytes;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** Nanotime the batch a drainer is holding was enqueued, 0 when none: the in-flight half of the oldest age. */
    private final AtomicLong inFlightEnqueuedAtNanos = new AtomicLong();

    public InProcessSpool(SubstrateProperties props) {
        this.maxQueueBytes = Math.max(1L, props.getQueueMaxBytes());
    }

    @Override
    public Admission append(String projectId, List<RawEntry> entries) {
        long bytes = measure(entries);
        if (bytes > maxQueueBytes) {
            // Never park a batch that cannot fit even an empty queue: its reservation could only be
            // satisfied by a drain that will never come, so it would hold the admission gate shut against
            // every other producer until something restarted the process. Answering OVERSIZE makes it
            // retryable, and the producer's resend can only succeed if it splits.
            log.warn(
                    "substrate batch exceeds the whole write budget — refused project={} spans={} bytes={} budget={}",
                    projectId,
                    entries.size(),
                    bytes,
                    maxQueueBytes);
            return Admission.OVERSIZE;
        }
        if (!reserve(bytes)) {
            log.warn(
                    "substrate write buffer full — shed batch project={} spans={} bytes={} queued={}/{}",
                    projectId,
                    entries.size(),
                    bytes,
                    queuedBytes.get(),
                    maxQueueBytes);
            return Admission.SHED;
        }
        queue.add(new Claimed(projectId, List.copyOf(entries), bytes, System.nanoTime(), null));
        return Admission.ACCEPTED;
    }

    @Override
    public Optional<Claimed> claim(Duration wait) throws InterruptedException {
        Claimed next = queue.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
        if (next != null) inFlightEnqueuedAtNanos.set(next.enqueuedAtNanos());
        return Optional.ofNullable(next);
    }

    @Override
    public void ack(Claimed claimed) {
        inFlightEnqueuedAtNanos.set(0);
        queuedBytes.addAndGet(-claimed.bytes());
    }

    @Override
    public void nack(Claimed claimed) {
        // Nothing durable to keep it in: the batch is gone, and the counter is the only trace of it.
        inFlightEnqueuedAtNanos.set(0);
        dropped.incrementAndGet();
        queuedBytes.addAndGet(-claimed.bytes());
    }

    @Override
    public Stats stats() {
        // The oldest unprocessed batch is the one a drainer is holding, if any, else the queue head: a
        // hung write with an empty queue must read as a growing age, not as idle.
        long now = System.nanoTime();
        long inFlight = inFlightEnqueuedAtNanos.get();
        Claimed head = queue.peek();
        long oldestNanos = inFlight != 0 ? inFlight : head == null ? 0 : head.enqueuedAtNanos();
        long oldestAgeMs = oldestNanos == 0 ? 0 : Math.max(0, (now - oldestNanos) / 1_000_000);
        return new Stats("memory", false, queue.size(), queuedBytes.get(), maxQueueBytes, oldestAgeMs, dropped.get());
    }

    @Override
    public double pressure() {
        return (double) queuedBytes.get() / maxQueueBytes;
    }

    /**
     * Take {@code bytes} out of the budget, or refuse. A CAS loop rather than a read-then-add: two
     * producers reading the same headroom and both adding would both be admitted, and the queue would
     * settle above its ceiling by however much the losing thread reserved.
     */
    private boolean reserve(long bytes) {
        while (true) {
            long current = queuedBytes.get();
            long next = current + bytes;
            if (next > maxQueueBytes) return false;
            if (queuedBytes.compareAndSet(current, next)) return true;
        }
    }

    /**
     * A batch's payload size — the character content it carries plus {@link #ENTRY_OVERHEAD_BYTES} per
     * entry. Summing lengths is cheap because the strings are already in hand. Character count, not
     * encoded length: for the ASCII-dominant JSON on this path they agree, and where they do not the
     * count under-reads by less than the per-entry allowance covers.
     */
    static long measure(List<RawEntry> entries) {
        long total = 0;
        for (RawEntry e : entries) {
            total += ENTRY_OVERHEAD_BYTES
                    + len(e.input())
                    + len(e.output())
                    + len(e.inputMessagesJson())
                    + len(e.outputMessagesJson())
                    + len(e.name())
                    + len(e.model())
                    + len(e.sourceExternalId())
                    + len(e.sourceUrl());
            Map<String, Object> metadata = e.metadata();
            if (metadata != null) {
                for (Map.Entry<String, Object> kv : metadata.entrySet()) {
                    total += len(kv.getKey());
                    total += kv.getValue() instanceof String s ? s.length() : METADATA_VALUE_BYTES;
                }
            }
        }
        return total;
    }

    private static long len(@Nullable String s) {
        return s == null ? 0 : s.length();
    }
}
