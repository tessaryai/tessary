// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;

/**
 * The billable units; {@link #wire()} matches the {@code metric_rollup.metric} CHECK.
 */
public enum UsageUnit {
    /** Ingested typed observations (spans), counted from {@code observation}. */
    INGESTED_SPANS("ingested_spans"),
    /** Counted on the hour they arrived. The billable unit. */
    INGESTED_TRACES("ingested_traces"),
    /** Layer 1: cheap classifier evaluations, counted from the per-classifier detection tables. */
    L1_EVALS("l1_evals");

    /**
     * The finest grain, and the only one a total over an arbitrary window may sum: a day row covers the same producer
     * rows as its 24 hour rows.
     */
    public static final String BUCKET_HOUR = "hour";

    public static final String BUCKET_DAY = "day";

    private static final java.util.Set<String> BUCKET_UNITS = java.util.Set.of(BUCKET_HOUR, BUCKET_DAY);

    private final String wire;

    UsageUnit(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static boolean isBucketUnit(String granularity) {
        return BUCKET_UNITS.contains(granularity);
    }

    public static UsageUnit fromWire(String wire) {
        for (UsageUnit u : values()) {
            if (u.wire.equals(wire)) {
                return u;
            }
        }
        throw new TessaryException(MeteringError.UNKNOWN_UNIT, wire);
    }
}
