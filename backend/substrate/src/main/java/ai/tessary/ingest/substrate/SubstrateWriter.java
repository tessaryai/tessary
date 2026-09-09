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
 * The batched/async write buffer in front of the substrate: ingest call-sites hand a fetched
 * {@link RawEntry} batch to {@link #enqueue} and return immediately; one or more drainer virtual threads
 * (one in {@code memory} mode, {@code tessary.ingest.spool.kafka.consumers} in {@code kafka} mode) run the {@link SpanBatchWriter} and writes {@code session → trace → span} off the hot path. This
 * decouples ingest/grading throughput from substrate persistence — a substrate failure never fails
 * (or slows) a grading run.
 *
 * <p><b>The buffer is an {@link IngestSpool} (#984).</b> The in-process spool is the default; a
 * Kafka-API spool is the opt-in for "accepted means persisted". This class is the drain over
 * whichever is wired: claim, redact once, write with retry, ack or nack.
 *
 * <p><b>Backpressure decision — shed rather than block, and tell the caller.</b> {@link #enqueue} hands
 * the batch to the spool (the in-process spool answers at once; the Kafka spool waits for the broker's
 * acknowledgement, bounded by its publish timeout). When the spool has no room the batch is SHED (WARN +
 * counter, no trace content in the log) rather than blocking the producer: the grading path's latency is
 * the priority, and the substrate self-heals on the next ingest of the same data because the whole write
 * path is idempotent (natural keys + {@code ON CONFLICT DO NOTHING}). The shed is reported back — {@link
 * #enqueue} returns {@code false} — so the ingest edge can answer the producer with a retryable error
 * rather than a 200: re-ingest only happens if something re-sends, and a shed the sender never learns
 * about is silent data loss, not self-healing.
 *
 * <p><b>In the in-process spool the bound is BYTES.</b> A batch carries its entries' payloads inline, so
 * its size is set by what the producer sent, not by how many batches are in flight: one bulk upload's
 * batches ranged from 10 spans to 825 spans and 24.5 MB. A count therefore bounds nothing — at 512
 * batches the queue once retained enough to exhaust the heap, and the {@code OutOfMemoryError} killed
 * this class's then-lone drainer thread outright. The spool reserves a batch's measured size against
 * {@code tessary.ingest.substrate.queue-max-bytes} before queueing it and releases exactly that
 * reservation on ack or nack, so the accounting cannot drift from what is retained. A batch larger than
 * the whole budget is refused rather than parked: parking it would wedge the queue against a
 * reservation that can never be satisfied.
 *
 * <p><b>The drainer is supervised.</b> {@link #ensureDrainerAlive()} is called from the throughput
 * reporter's tick and starts a replacement if the thread is gone. {@code drainLoop}'s
 * {@code catch (RuntimeException)} is deliberately NOT widened to {@code Throwable}: surviving an
 * {@code OutOfMemoryError} says nothing about whether the heap is still coherent, and {@code Error}'s own
 * contract is that a reasonable application should not try to catch it. The JVM is configured to dump and
 * exit on OOM instead (see {@code backend/Dockerfile}), which makes the orchestrator — not a catch block
 * — responsible for the restart. The supervisor here covers every other way a thread can be lost.
 *
 * <p><b>Delivery — at-least-once, first-write-wins.</b> The drainer retries a failed batch up to
 * {@code tessary.ingest.substrate.max-attempts} with linear backoff; a replay of a partially-written
 * batch only fills in missing rows. What happens to batches accepted but not yet written on a crash
 * is the spool's contract: the in-process spool loses them (accepted, and published as such in the
 * ingest runbook); the Kafka spool replays them, because it commits an offset only after the write.
 *
 * <p><b>Throughput SLO.</b> Producer-side: in the in-process mode {@code enqueue} does no I/O and never
 * blocks, so the tee adds O(1) work per ingest batch regardless of burst; in the Kafka mode it costs one
 * acknowledged publish. Drainer-side target: sustain ≥ 200
 * observations/second against the reference Postgres (the Testcontainers setup) — a full
 * ingest batch of ~1000 spans drains in ≤ 5s.
 * {@code SubstrateWriteIntegrationTest} exercises a 1000-span burst against this budget
 * (with CI headroom) and asserts the enqueue side returns in milliseconds.
 *
 * <p>No LLM/classifier call happens anywhere on this path — all signal evaluation is async.
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
     * Entries are taken as-is — content is never clipped here or anywhere downstream (telemetry is bound
     * by count, never truncation).
     *
     * <p><b>PII redaction guard — applied on the DRAIN side.</b> The project's enabled redaction rules
     * are applied by the drainer, immediately before the substrate write ({@link
     * RedactionService#redactBatch}), so no unredacted PII is ever <em>persisted</em>. Redaction is a
     * deliberate, operator-authored transform (the playground), <em>not</em> a silent truncation — the
     * content is substituted, never clipped. A project with no rules (or the guard disabled) is a cheap
     * no-op that hands the batch through unchanged.
     *
     * <p>It used to run here, before the {@code offer}, which broke the O(1) contract above: on
     * 2026-07-31 a thread dump taken at 100% CPU showed {@code OtlpTraceController.export} →
     * {@code enqueue} → {@code redactBatch} → {@code java.util.regex}, i.e. full regex over every entry
     * <em>synchronously on the HTTP request thread</em>, saturating both vCPUs and stalling the sender.
     * The queue, the drainer and the shed policy were all already here — the expensive work was simply
     * on the wrong side of the boundary.
     *
     * <p><b>What moving it changes, precisely:</b> unredacted entries now sit in the in-process queue
     * for the (bounded) time before the drainer reaches them. That is not a new exposure of data the
     * process did not already hold — the raw payload arrived in this same heap on the request thread —
     * and the guarantee that matters, "nothing unredacted is persisted", is unchanged because redaction
     * still precedes every write. It does mean a heap dump taken mid-flight could contain unredacted
     * content that previously it would not have. Accepted deliberately; if that trade is ever
     * unacceptable, the fix is a durable redacted-on-write queue, not moving the cost back onto the
     * request thread.
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
                // kill the single substrate-writer thread permanently — every later batch shed, and
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
                // Settle the claim on every path out of the batch — written, retried to exhaustion, or
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
     * <p>This exists because losing the drainer is unrecoverable and, until now, silent: the queue simply
     * stopped draining while the HTTP surface kept accepting and answering. SEI CERT TPS03-J puts it as a
     * requirement rather than a nicety — a task must provide some mechanism for notifying the application
     * when it terminates abnormally. Note the deliberate non-choice of {@code scheduleWithFixedDelay} for
     * the drain loop itself: a task that throws there has its subsequent executions <em>suppressed</em>,
     * per its own javadoc, which is this same failure wearing a different hat.
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
     * <p>Scoped to what this process enqueued — see {@link #pending}. A Kafka-spool deployment asking
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
