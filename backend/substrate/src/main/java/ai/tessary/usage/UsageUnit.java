// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;

/**
 * The billable units; {@link #wire()} matches the {@code metric_rollup.metric} CHECK. {@link #STORAGE} is a level
 * snapshot gated by {@code tessary.metering.storage-enabled} until a billing basis is ratified.
 */
public enum UsageUnit {
    /** Ingested typed observations (spans), counted from {@code observation}. */
    INGESTED_SPANS("ingested_spans"),
    /** Counted on the hour they arrived. The billable unit. */
    INGESTED_TRACES("ingested_traces"),
    /** Retired. Kept because {@link #fromWire} 422s on an unknown value and historical rows must still read back. */
    L2_EVALS("l2_evals"),
    /** Layer 1: cheap classifier evaluations, counted from the per-classifier detection tables. */
    L1_EVALS("l1_evals"),
    /** Retired; see {@link #L2_EVALS}. */
    LLM_TOKENS("llm_tokens"),
    /** Retired; see {@link #L2_EVALS}. Micro-dollars so {@code long} sums stay exact; divide by 1e6 at display. */
    LLM_COST_MICRO_USD_PLATFORM("llm_cost_micro_usd_platform"),
    /** Retired; see {@link #L2_EVALS}. */
    LLM_COST_MICRO_USD_BYO("llm_cost_micro_usd_byo"),
    STORAGE("storage");

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

    /** Everything but {@link #STORAGE} and the retired units. */
    public static UsageUnit[] metered() {
        return new UsageUnit[] {INGESTED_SPANS, INGESTED_TRACES, L1_EVALS};
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
