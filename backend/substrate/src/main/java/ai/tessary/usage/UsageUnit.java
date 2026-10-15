// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;

/**
 * The billable units the metering layer meters. The wire/DB value is the snake_case
 * {@link #wire()} string, matching the {@code metric_rollup.metric} CHECK.
 *
 * <p>The two eval units are named for the layer they bill: cheap per-event filtering that runs on
 * everything, and the expensive escalation applied only to what the filter flagged. That is the
 * distinction a customer is charged on, so it is the distinction the unit names state.
 */
public enum UsageUnit {
    /** Ingested typed observations (spans), counted from {@code observation}. */
    INGESTED_SPANS("ingested_spans"),
    /** Ingested traces, counted from {@code trace} on the hour they arrived. The billable unit. */
    INGESTED_TRACES("ingested_traces"),
    /** Layer 1: cheap classifier evaluations, counted from the per-classifier detection tables. */
    L1_EVALS("l1_evals");

    /**
     * The hourly bucket grain. The FINEST grain the worker produces and therefore the only one a total
     * over an arbitrary window may sum: a day row covers the same producer rows as the 24 hour rows beside
     * it, so any read that does not pin a grain double-counts.
     */
    public static final String BUCKET_HOUR = "hour";

    /** The daily bucket grain: the same producer rows re-aggregated over a whole closed day. */
    public static final String BUCKET_DAY = "day";

    /** The bucket grains the {@code metric_rollup} CHECK reserves: hourly and daily. */
    private static final java.util.Set<String> BUCKET_UNITS = java.util.Set.of(BUCKET_HOUR, BUCKET_DAY);

    private final String wire;

    UsageUnit(String wire) {
        this.wire = wire;
    }

    /** The snake_case wire/DB identifier (matches the {@code metric_rollup.metric} CHECK). */
    public String wire() {
        return wire;
    }

    /** Whether {@code granularity} is an allow-listed grain ({@code hour} or {@code day}). */
    public static boolean isBucketUnit(String granularity) {
        return BUCKET_UNITS.contains(granularity);
    }

    /** Resolve a snake_case wire unit to the enum, or a {@code 422} if unknown. */
    public static UsageUnit fromWire(String wire) {
        for (UsageUnit u : values()) {
            if (u.wire.equals(wire)) {
                return u;
            }
        }
        throw new TessaryException(MeteringError.UNKNOWN_UNIT, wire);
    }
}
