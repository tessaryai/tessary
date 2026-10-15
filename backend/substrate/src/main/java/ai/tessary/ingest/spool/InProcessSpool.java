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
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default spool: in memory, bounded by payload bytes; a restart loses what is queued. Bytes are the only ceiling
 * because batches span an ~80x size range, and the per-entry allowance in {@link #measure} already bounds the batch
 * count.
 */
public final class InProcessSpool implements IngestSpool {

    private static final Logger log = LoggerFactory.getLogger(InProcessSpool.class);

    /** Generous for a record of fifteen references. */
    private static final long ENTRY_OVERHEAD_BYTES = 512;

    private static final long METADATA_VALUE_BYTES = 32;

    private final BlockingQueue<Claimed> queue = new LinkedBlockingQueue<>();
    private final long maxQueueBytes;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** 0 when none: the in-flight half of the oldest age. */
    private final AtomicLong inFlightEnqueuedAtNanos = new AtomicLong();

    private final LongSupplier nanoTime;

    public InProcessSpool(SubstrateProperties props) {
        this(props, System::nanoTime);
    }

    InProcessSpool(SubstrateProperties props, LongSupplier nanoTime) {
        this.maxQueueBytes = Math.max(1L, props.queueMaxBytes());
        this.nanoTime = nanoTime;
    }

    @Override
    public Admission append(String projectId, List<RawEntry> entries) {
        long bytes = measure(entries);
        if (bytes > maxQueueBytes) {
            // A batch that cannot fit an empty queue would hold the admission gate shut forever; OVERSIZE tells the
            // producer to split.
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
        queue.add(new Claimed(projectId, List.copyOf(entries), bytes, nanoTime.getAsLong(), null));
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
        // The counter is the only trace of the dropped batch.
        inFlightEnqueuedAtNanos.set(0);
        dropped.incrementAndGet();
        queuedBytes.addAndGet(-claimed.bytes());
    }

    @Override
    public Stats stats() {
        // A hung write with an empty queue must read as a growing age, not as idle.
        long now = nanoTime.getAsLong();
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

    /** One atomic update: a read-then-add lets two producers both be admitted over the ceiling. */
    private boolean reserve(long bytes) {
        long before = queuedBytes.getAndUpdate(current -> current + bytes > maxQueueBytes ? current : current + bytes);
        return before + bytes <= maxQueueBytes;
    }

    /**
     * Character count, not encoded length: for this ASCII-dominant JSON they agree, and any under-read is within the
     * per-entry allowance.
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
                    + len(e.sourceExternalId());
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
