// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.telemetry;

/**
 * The heartbeat ping's coarse count buckets (devdocs/reference/telemetry-contract.md §1) — closed enums,
 * not free-form strings, exactly as the contract requires: "Both sides of the ping exchange must agree
 * on the exact boundaries; a 'coarse bucket' left as an illustrative example... defeats this doc's
 * purpose." Widening a boundary here without updating the doc (or vice versa) is exactly the drift
 * Rule 4 exists to catch.
 */
public final class TelemetryBuckets {

    private TelemetryBuckets() {}

    /** {@code org_count_bucket} / {@code project_count_bucket} — contract §1's closed set. */
    public enum CountBucket {
        ZERO("0", 0, 0),
        ONE_TO_FIVE("1-5", 1, 5),
        SIX_TO_25("6-25", 6, 25),
        TWENTYSIX_TO_100("26-100", 26, 100),
        HUNDREDONE_TO_500("101-500", 101, 500),
        FIVE_HUNDRED_PLUS("500+", 501, Long.MAX_VALUE);

        private final String wire;
        private final long min;
        private final long max;

        CountBucket(String wire, long min, long max) {
            this.wire = wire;
            this.min = min;
            this.max = max;
        }

        public String wire() {
            return wire;
        }

        /** The bucket a raw count falls into. Boundaries are inclusive on both ends, contiguous, and
         *  exhaustive for any {@code count >= 0} — every non-negative count matches exactly one. */
        public static CountBucket forCount(long count) {
            for (CountBucket b : values()) {
                if (count >= b.min && count <= b.max) return b;
            }
            // Unreachable for count >= 0: FIVE_HUNDRED_PLUS's max is Long.MAX_VALUE. A negative count
            // is a caller bug (a COUNT(*) cannot be negative), not a bucket this ping has a slot for.
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
    }

    /** {@code trace_volume_bucket} (traces/day, rolling 24h) — contract §1's closed set. Deliberately a
     *  separate enum from {@link CountBucket}: the boundaries and the unit (traces, not orgs/projects)
     *  differ, and the contract enumerates them as two distinct sets. */
    public enum VolumeBucket {
        ZERO("0", 0, 0),
        ONE_TO_100("1-100", 1, 100),
        HUNDREDONE_TO_1K("101-1k", 101, 1_000),
        ONEK_TO_10K("1k-10k", 1_001, 10_000),
        TENK_TO_100K("10k-100k", 10_001, 100_000),
        HUNDREDK_PLUS("100k+", 100_001, Long.MAX_VALUE);

        private final String wire;
        private final long min;
        private final long max;

        VolumeBucket(String wire, long min, long max) {
            this.wire = wire;
            this.min = min;
            this.max = max;
        }

        public String wire() {
            return wire;
        }

        /** The bucket a raw rolling-24h trace count falls into. Same inclusive/contiguous/exhaustive
         *  contract as {@link CountBucket#forCount}. */
        public static VolumeBucket forCount(long count) {
            for (VolumeBucket b : values()) {
                if (count >= b.min && count <= b.max) return b;
            }
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
    }
}
