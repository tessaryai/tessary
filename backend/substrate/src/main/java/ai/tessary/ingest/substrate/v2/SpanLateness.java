// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.stereotype.Component;

/**
 * How far behind its trace's last rollup each span arrived (substrate-model.md §7.6); its tail sets the rollup windows.
 * Negative lateness is a producer clock stepping backward, otherwise invisible. Drained counts logged to Loki, since
 * this module declares no Micrometer.
 */
@Component
public class SpanLateness {

    /** The final bucket is everything beyond the last edge. */
    private static final long[] EDGES = {0L, 1_000L, 10_000L, 60_000L, 300_000L};

    private static final String[] BUCKET_FIELDS = {
        "late_negative", "late_lt_1s", "late_lt_10s", "late_lt_60s", "late_lt_5m", "late_ge_5m"
    };

    /** In {@link #drain()} order, overflow last. */
    public static String[] fields() {
        return BUCKET_FIELDS.clone();
    }

    private final AtomicLongArray buckets = new AtomicLongArray(BUCKET_FIELDS.length);

    /** Negative values are the backward-clock bucket. */
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

    /** Deltas, so "is the tail growing now" needs no subtraction. */
    public long[] drain() {
        long[] out = new long[BUCKET_FIELDS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = buckets.getAndSet(i, 0);
        }
        return out;
    }
}
