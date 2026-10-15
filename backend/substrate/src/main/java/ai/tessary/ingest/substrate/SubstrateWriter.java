// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.spool.IngestSpool;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
import ai.tessary.redaction.RedactionService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Backpressure sheds rather than blocks: the substrate self-heals on re-ingest (natural keys +
 * {@code ON CONFLICT DO NOTHING}), and {@link #enqueue} returns false so the edge answers a retryable error. The
 * in-process bound is bytes; an over-budget batch is refused, since parking it would wedge the queue. {@code drainLoop}
 * catches only RuntimeException: after an OOM the heap may be incoherent, and the JVM exits instead. Delivery is
 * at-least-once, first-write-wins.
 */
@Component
public class SubstrateWriter {

    private static final Logger log = LoggerFactory.getLogger(SubstrateWriter.class);

    private static final Duration CLAIM_WAIT = Duration.ofSeconds(1);

    private final SpanBatchWriter writer;
    private final SubstrateProperties props;
    private final RedactionService redaction;
    private final IngestSpool spool;
    private final List<Thread> drainers = new CopyOnWriteArrayList<>();
    private final int drainerCount;

    private final AtomicLong enqueuedBatches = new AtomicLong();
    private final AtomicLong shedBatches = new AtomicLong();
    private final AtomicLong oversizeBatches = new AtomicLong();
    private final AtomicLong drainerRestarts = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();

    /**
     * Under the Kafka spool a batch accepted before a restart is acked after it, so the decrement floors at zero or
     * {@code awaitIdle} could never see idle again.
     */
    private final AtomicLong pending = new AtomicLong();

    private volatile boolean running = true;

    public SubstrateWriter(
            SpanBatchWriter writer, SubstrateProperties props, RedactionService redaction, IngestSpool spool) {
        this.writer = writer;
        this.props = props;
        this.redaction = redaction;
        this.spool = spool;
        this.drainerCount = spool.drainers();
        for (int i = 0; i < drainerCount; i++) drainers.add(startDrainer(i));
    }

    private Thread startDrainer(int index) {
        return Thread.ofVirtual().name("substrate-writer-" + index).start(this::drainLoop);
    }

    /**
     * Redaction runs on the drain side, before every write, to keep a regex pass off the request thread; the raw
     * payload is already in this heap, so that is no new exposure. @return false only when the batch was shed; an empty
     * batch is true
     */
    public boolean enqueue(String projectId, List<RawEntry> entries) {
        if (entries.isEmpty()) return true;
        pending.incrementAndGet(); // before the append: never visible-empty while a batch is queued
        IngestSpool.Admission admission;
        try {
            admission = spool.append(projectId, entries);
        } catch (RuntimeException e) {
            // A spool that throws did not take the batch: same answer as a shed.
            admission = IngestSpool.Admission.SHED;
            log.warn(
                    "substrate spool append failed project={} error={}",
                    projectId,
                    e.getClass().getSimpleName());
            log.debug("substrate spool append failure detail", e);
        }
        switch (admission) {
            case ACCEPTED -> {
                enqueuedBatches.incrementAndGet();
                return true;
            }
            case OVERSIZE -> oversizeBatches.incrementAndGet();
            case SHED -> shedBatches.incrementAndGet();
        }
        pending.decrementAndGet();
        return false;
    }

    private void drainLoop() {
        while (running) {
            IngestSpool.Claimed batch;
            try {
                Optional<IngestSpool.Claimed> claimed = spool.claim(CLAIM_WAIT);
                if (claimed.isEmpty()) continue;
                batch = claimed.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean written = false;
            try {
                // Redact once, outside writeWithRetry, so a retry does not pay the regex cost again.
                List<RawEntry> redacted = redaction.redactBatch(batch.projectId(), batch.entries());
                written = writeWithRetry(batch.projectId(), redacted);
            } catch (RuntimeException e) {
                // Must catch here: redactBatch may hit JDBC on a cache miss before writeWithRetry's handler, and one
                // escape would kill the lone drainer permanently. No throwable on the WARN: a Postgres error can echo
                // raw trace content, and WARN egresses to Loki.
                failedBatches.incrementAndGet();
                log.warn(
                        "substrate batch dropped before write project={} spans={} error={}",
                        batch.projectId(),
                        batch.entries().size(),
                        e.getClass().getSimpleName());
                log.debug("substrate pre-write failure detail", e);
            } finally {
                // Settle the claim on every path: an open claim leaks until the spool refuses everything, and pending
                // must fall even when settlement throws.
                try {
                    if (written) {
                        spool.ack(batch);
                    } else {
                        spool.nack(batch);
                    }
                } catch (RuntimeException e) {
                    log.warn(
                            "substrate spool settlement failed project={} written={} error={}",
                            batch.projectId(),
                            written,
                            e.getClass().getSimpleName());
                    log.debug("substrate spool settlement failure detail", e);
                } finally {
                    pending.updateAndGet(p -> Math.max(0, p - 1));
                }
            }
        }
    }

    /**
     * Losing the drainer is otherwise silent and unrecoverable. Not {@code scheduleWithFixedDelay}, which suppresses
     * later runs after a throw.
     */
    public void ensureDrainerAlive() {
        if (!running) return;
        if (drainerAlive()) return;
        synchronized (this) {
            if (!running) return;
            for (int i = 0; i < drainers.size(); i++) {
                Thread current = drainers.get(i);
                if (current.isAlive()) continue;
                drainerRestarts.incrementAndGet();
                log.error(
                        "substrate writer drainer {} was not alive — starting a replacement restarts={}",
                        i,
                        drainerRestarts.get());
                drainers.set(i, startDrainer(i));
            }
        }
    }

    private boolean writeWithRetry(String projectId, List<RawEntry> entries) {
        int attempts = Math.max(1, props.maxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                writer.write(projectId, entries);
                return true;
            } catch (RuntimeException e) {
                // No throwable on the WARN: a Postgres error can echo raw trace content, and WARN egresses to Loki.
                log.warn(
                        "substrate batch write failed project={} spans={} attempt={}/{}",
                        projectId,
                        entries.size(),
                        attempt,
                        attempts);
                log.debug("substrate batch write failure detail project={}", projectId, e);
                if (attempt < attempts) {
                    try {
                        Thread.sleep(Math.max(0, props.retryBackoffMs()) * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        failedBatches.incrementAndGet();
                        return false;
                    }
                }
            }
        }
        failedBatches.incrementAndGet();
        return false;
    }

    /** Leaves {@code running} true so the supervisor sees a thread that died rather than one asked to stop. */
    void killDrainerForTest() {
        for (Thread current : drainers) current.interrupt();
    }

    boolean awaitDrainerDeath(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!drainerAlive()) return true;
            Thread.sleep(20);
        }
        return !drainerAlive();
    }

    /** Scoped to what this process enqueued; Kafka lag is in {@link IngestSpool#stats()}. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (pending.get() == 0) return true;
            Thread.sleep(20);
        }
        return pending.get() == 0;
    }

    public long enqueuedBatches() {
        return enqueuedBatches.get();
    }

    public long shedBatches() {
        return shedBatches.get();
    }

    public long failedBatches() {
        return failedBatches.get();
    }

    public long oversizeBatches() {
        return oversizeBatches.get();
    }

    public long drainerRestarts() {
        return drainerRestarts.get();
    }

    public boolean drainerAlive() {
        return drainersAlive() == drainerCount;
    }

    public int drainersAlive() {
        int alive = 0;
        for (Thread current : drainers) {
            if (current.isAlive()) alive++;
        }
        return alive;
    }

    public int drainerCount() {
        return drainerCount;
    }

    public IngestSpool.Stats spoolStats() {
        return spool.stats();
    }

    public double queueBytesUsedFraction() {
        return spool.pressure();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        for (Thread current : drainers) current.interrupt();
    }
}
