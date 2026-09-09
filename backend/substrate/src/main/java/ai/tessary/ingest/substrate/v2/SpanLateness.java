// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.stereotype.Component;

/**
 * The span-lateness histogram the spec requires (substrate-model.md §7.6): how far behind its trace's
 * last rollup a span was when it arrived — {@code span.event_ts} minus that trace's
 * {@code rolled_up_through} at the moment of the write.
 *
 * <p><b>What it is for.</b> The ten-second and two-second rollup windows in §7.4 are stated as starting
 * points, and this histogram — not intuition — is what sets their final values. A growing tail is the
 * signal that they are wrong: those are spans landing after their trace settled, each one a re-fire and a
 * number that was briefly shown too low.
 *
 * <p><b>Negative lateness is a finding, not noise.</b> §6.2 accepts that a producer whose clock steps
 * backward can discard a span's final version, because last-write-wins orders by the producer's own
 * timestamp. That failure is otherwise invisible; here it is a bucket.
 *
 * <p>Bucket counts, drained per reporting interval, rather than a Micrometer meter: the substrate module
 * deliberately declares no Micrometer (it arrives with actuator, which only {@code app} has), and Loki
 * already turns every structured field into something graphable.
 */
@Component
public class SpanLateness {

    /** Upper edges in milliseconds; the final bucket is everything beyond the last edge. */
    private static final long[] EDGES = {0L, 1_000L, 10_000L, 60_000L, 300_000L};

    private static final String[] BUCKET_FIELDS = {
        "late_negative", "late_lt_1s", "late_lt_10s", "late_lt_60s", "late_lt_5m", "late_ge_5m"
    };

    /** Structured-log field names, in {@link #drain()} order: one per bucket, ascending, overflow last. */
    public static String[] fields() {
        return BUCKET_FIELDS.clone();
    }

    private final AtomicLongArray buckets = new AtomicLongArray(BUCKET_FIELDS.length);

    /** Record one arrival's lateness in milliseconds. Negative values are the backward-clock bucket. */
    public void record(long latenessMillis) {
        buckets.incrementAndGet(bucketOf(latenessMillis));
    }

    private static int bucketOf(long millis) {
        if (millis < EDGES[0]) return 0;
        for (int i = 1; i < EDGES.length; i++) {
            if (millis < EDGES[i]) return i;
        }
        return BUCKET_FIELDS.length - 1;
    }

    /**
     * Take and reset the interval's counts. Deltas rather than totals, for the same reason the throughput
     * line carries deltas: "is the tail growing right now" should not be a subtraction the reader does in
     * their head at 3am.
     */
    public long[] drain() {
        long[] out = new long[BUCKET_FIELDS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = buckets.getAndSet(i, 0);
        }
        return out;
    }
}
