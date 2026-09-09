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
 * The batched/async write buffer in front of the substrate: ingest call sites hand a fetched
 * {@link RawEntry} batch to {@link #enqueue} and return immediately; one or more drainer virtual threads
 * run the {@link SpanBatchWriter} and write {@code session -> trace -> span} off the hot path. This
 * decouples ingest/grading throughput from substrate persistence: a substrate failure never fails or
 * slows a grading run.
 *
 * <p>The buffer is an {@link IngestSpool}. The in-process spool is the default; a Kafka-API spool is the
 * opt-in for "accepted means persisted". This class is the drain over whichever is wired: claim, redact
 * once, write with retry, ack or nack.
 *
 * <p>Backpressure sheds rather than blocks. {@link #enqueue} hands the batch to the spool; when the spool
 * has no room the batch is shed (warn + counter, no trace content in the log) rather than blocking the
 * producer, since the grading path's latency is the priority and the substrate self-heals on the next
 * ingest of the same data (natural keys + {@code ON CONFLICT DO NOTHING}). {@link #enqueue} returns
 * {@code false} on a shed, so the ingest edge can answer with a retryable error rather than a 200.
 *
 * <p>In the in-process spool the bound is bytes, not batch count: a batch carries its entries' payloads
 * inline, so its size is set by what the producer sent. The spool reserves a batch's measured size
 * against {@code tessary.ingest.substrate.queue-max-bytes} before queueing it and releases exactly that
 * reservation on ack or nack. A batch larger than the whole budget is refused rather than parked, which
 * would wedge the queue against a reservation that can never be satisfied.
 *
 * <p>The drainer is supervised: {@link #ensureDrainerAlive()} is called from the throughput reporter's
 * tick and starts a replacement if the thread is gone. {@code drainLoop}'s
 * {@code catch (RuntimeException)} is deliberately not widened to {@code Throwable}: surviving an
 * {@code OutOfMemoryError} says nothing about whether the heap is still coherent, and the JVM is
 * configured to dump and exit on OOM instead, making the orchestrator responsible for that restart.
 *
 * <p>Delivery is at-least-once, first-write-wins. The drainer retries a failed batch up to
 * {@code tessary.ingest.substrate.max-attempts} with linear backoff; a replay of a partially-written
 * batch only fills in missing rows. What happens to a batch accepted but not yet written on a crash is
 * the spool's contract: the in-process spool loses it, the Kafka spool replays it, since it commits an
 * offset only after the write.
 *
 * <p>Throughput SLO: producer-side, {@code enqueue} does no I/O and never blocks in the in-process mode;
 * drainer-side, the target is sustaining >= 200 observations/second against the reference Postgres, with
 * a full ~1000-span batch draining in <= 5s. {@code SubstrateWriteIntegrationTest} exercises that budget.
 *
 * <p>No LLM/classifier call happens anywhere on this path; all signal evaluation is async.
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
     * Batches accepted and not yet acked or nacked, so {@link #awaitIdle} sees in-flight work too.
     *
     * <p>Only ever read through {@link #awaitIdle}, and only meaningful for work this process both
     * enqueued and drained. Under the Kafka spool the two sides can belong to different runs: a batch
     * accepted before a restart is claimed by the drainer after it, decrementing for an increment that
     * never happened here. The decrement therefore floors at zero rather than going negative, which
     * would leave {@code awaitIdle} unable to observe an idle writer for the rest of the process's life.
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
     * Tee one ingest batch to the substrate. Non-blocking, never throws, no-op when disabled or empty.
     * Entries are taken as-is, content is never clipped here or anywhere downstream (telemetry is bound
     * by count, never truncation).
     *
     * <p>PII redaction runs on the drain side, not here. The project's enabled redaction rules are
     * applied by the drainer, immediately before the substrate write ({@link
     * RedactionService#redactBatch}), so no unredacted PII is ever persisted. Redaction substitutes
     * content rather than clipping it; a project with no rules is a cheap no-op that hands the batch
     * through unchanged. Running redaction here instead would put a full regex pass over every entry on
     * the HTTP request thread, which is why it stays on the drain side: unredacted entries sit in the
     * in-process queue for a bounded time, but that is not new exposure, since the raw payload already
     * arrived in this same heap on the request thread, and redaction still precedes every write.
     *
     * @return {@code true} when the batch is the substrate's problem now, {@code false} ONLY when the
     *     bounded queue was full and the batch was shed. An empty batch is {@code true}: nothing was
     *     lost, so there is nothing for the producer to resend.
     */
    public boolean enqueue(String projectId, List<RawEntry> entries) {
        if (entries.isEmpty()) return true;
        pending.incrementAndGet(); // before the append: never visible-empty while a batch is queued
        IngestSpool.Admission admission;
        try {
            admission = spool.append(projectId, entries);
        } catch (RuntimeException e) {
            // A spool that throws did not take the batch: same answer as a shed, and the producer retries.
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
                // Redact ONCE, outside writeWithRetry: a retry re-writes the same already-redacted
                // batch rather than paying the regex cost again per attempt.
                List<RawEntry> redacted = redaction.redactBatch(batch.projectId(), batch.entries());
                written = writeWithRetry(batch.projectId(), redacted);
            } catch (RuntimeException e) {
                // MUST catch here, not rely on writeWithRetry's internal handler: redactBatch runs
                // BEFORE that method is entered, and compiledFor() does a JdbcClient read on a cache
                // miss. Without this, one transient DataAccessException would escape drainLoop and
                // kill the single substrate-writer thread permanently, every later batch shed, and
                // awaitIdle never true again. On the request thread (where this used to run) the same
                // throw failed one HTTP request; on a lone drainer it is unrecoverable.
                // No throwable on the WARN: a Postgres error can echo raw trace content into its
                // message and WARN egresses to Loki (same convention as writeWithRetry below).
                failedBatches.incrementAndGet();
                log.warn(
                        "substrate batch dropped before write project={} spans={} error={}",
                        batch.projectId(),
                        batch.entries().size(),
                        e.getClass().getSimpleName());
                log.debug("substrate pre-write failure detail", e);
            } finally {
                // Settle the claim on every path out of the batch, written, retried to exhaustion, or
                // dropped before the write. A claim that can be left open is a leak that ends with the
                // spool refusing everything while holding nothing; and pending must fall even when the
                // settlement itself throws (a broker commit that fails), or awaitIdle never comes true.
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
     * Restart the drainer if it is gone. Called from the throughput reporter's tick, so the check costs
     * one {@code isAlive()} per reporting interval and needs no scheduler of its own.
     *
     * <p>This exists because losing the drainer is otherwise unrecoverable and silent: the queue simply
     * stops draining while the HTTP surface keeps accepting and answering. Note the deliberate non-choice
     * of {@code scheduleWithFixedDelay} for the drain loop itself: a task that throws there has its
     * subsequent executions suppressed, which is the same failure wearing a different hat.
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
        int attempts = Math.max(1, props.getMaxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                writer.write(projectId, entries);
                return true;
            } catch (RuntimeException e) {
                // No throwable on the WARN: a Postgres error can echo raw trace content in its
                // message, and WARN egresses to Loki. Detail stays local at DEBUG (keeps raw
                // trace content out of Loki).
                log.warn(
                        "substrate batch write failed project={} spans={} attempt={}/{}",
                        projectId,
                        entries.size(),
                        attempt,
                        attempts);
                log.debug("substrate batch write failure detail project={}", projectId, e);
                if (attempt < attempts) {
                    try {
                        Thread.sleep(Math.max(0, props.getRetryBackoffMs()) * attempt);
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

    /**
     * Test hook: stop the drainer the way a fatal throw would, leaving {@code running} true so the
     * supervisor sees a thread that died rather than one that was asked to stop.
     */
    void killDrainerForTest() {
        for (Thread current : drainers) current.interrupt();
    }

    /** Test hook: wait for the drainer to actually be gone before asserting on the supervisor. */
    boolean awaitDrainerDeath(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!drainerAlive()) return true;
            Thread.sleep(20);
        }
        return !drainerAlive();
    }

    /**
     * Test hook: wait until the buffer is fully drained (no batch queued or in flight).
     *
     * <p>Scoped to what this process enqueued, see {@link #pending}. A Kafka-spool deployment asking
     * "is the spool drained" wants the broker's lag, which {@link IngestSpool#stats()} reports and the
     * ingest health indicator publishes; this counter cannot see a batch another run accepted.
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (pending.get() == 0) return true;
            Thread.sleep(20);
        }
        return pending.get() == 0;
    }

    /** Batches the spool accepted. */
    public long enqueuedBatches() {
        return enqueuedBatches.get();
    }

    /** Batches refused because the spool had no room; each answered the producer with a retryable status. */
    public long shedBatches() {
        return shedBatches.get();
    }

    /** Batches that exhausted their write retries or failed before the write; nacked to the spool. */
    public long failedBatches() {
        return failedBatches.get();
    }

    /** Batches larger than the whole budget, refused for the producer to split. */
    public long oversizeBatches() {
        return oversizeBatches.get();
    }

    /** Times the supervisor found the drainer dead and started a replacement. */
    public long drainerRestarts() {
        return drainerRestarts.get();
    }

    /** True only when every drainer is alive; the supervisor restarts the ones that are not. */
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

    /** The spool's own view: mode, depth, bytes, oldest age, dead-lettered count. */
    public IngestSpool.Stats spoolStats() {
        return spool.stats();
    }

    /** Test seam; the throughput line and the health group read the spool's stats directly. */
    public int queueDepth() {
        return (int) Math.min(Integer.MAX_VALUE, spool.stats().depth());
    }

    /** Test seam, as {@link #queueDepth()}. */
    public long queueBytes() {
        return spool.stats().bytes();
    }

    /** The spool's admission pressure, read on every push by the pre-decode gate ({@code OtlpIngestService#shouldRefuse}). */
    public double queueBytesUsedFraction() {
        return spool.pressure();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        for (Thread current : drainers) current.interrupt();
    }
}
