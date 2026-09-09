// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;

/**
 * The billable units the metering layer meters. The wire/DB value is the snake_case
 * {@link #wire()} string, matching the {@code metric_rollup.metric} CHECK.
 *
 * <p><b>The two eval units are named for the LAYER they bill.</b> They were {@code signal_evals} and
 * {@code grader_runs} until 0095 — names that described an architecture the classifier-pipeline cutover
 * replaced. What a bill is actually made of is the cascade's two layers: cheap per-event filtering that
 * runs on everything, and the expensive escalation applied only to what the filter flagged. That is the
 * distinction a customer is charged on, so it is the distinction the unit names state.
 *
 * <p>{@link #STORAGE} is a LEVEL snapshot, not an event-stream count, and is produced only
 * when {@code tessary.metering.storage-enabled} is set: its billable basis (bytes vs row-count vs
 * retention-days) is an open billing product decision, so it stays gated until a deployment ratifies the
 * basis and opts in. The v1 basis is ingested span rows at rest.
 */
public enum UsageUnit {
    /** Ingested typed observations (spans) — counted from {@code observation}. */
    INGESTED_SPANS("ingested_spans"),
    /**
     * Layer 2: escalation analysis of a finding. <b>RETIRED by Track A and no longer produced</b> — it
     * was counted from {@code verdict}, the grading store, which no longer exists. The constant stays
     * because {@link #fromWire} 422s on an unknown value and historical {@code metric_rollup} rows
     * written under {@code l2_evals} survive the removal and must still read back.
     */
    L2_EVALS("l2_evals"),
    /** Layer 1: cheap classifier evaluations — counted from the per-classifier detection tables. */
    L1_EVALS("l1_evals"),
    /** LLM tokens consumed by grading. <b>RETIRED by Track A</b> — see {@link #L2_EVALS}. */
    LLM_TOKENS("llm_tokens"),
    /**
     * USD cost of grading paid on the PLATFORM's own credential, in <b>micro-dollars</b> (1e-6 USD).
     * <b>RETIRED by Track A</b> — see {@link #L2_EVALS}.
     *
     * <p>Micro-dollars, not dollars, because {@code metric_rollup} values move through this layer as
     * {@code long}: money must not be truncated to whole dollars, and integer micros sum exactly with no
     * floating-point drift across a month. The scale is in the unit name so no reader can mistake the
     * magnitude. Divide by 1e6 at the display boundary.
     *
     * <p>Live per-lane, per-model LLM spend did NOT go with it: that view reads the {@code llm_call}
     * ledger through {@code LlmUsageQueryRepository}, a separate system this unit never fed.
     */
    LLM_COST_MICRO_USD_PLATFORM("llm_cost_micro_usd_platform"),
    /** As {@link #LLM_COST_MICRO_USD_PLATFORM}, for a CUSTOMER's own credential. <b>RETIRED by Track A</b>. */
    LLM_COST_MICRO_USD_BYO("llm_cost_micro_usd_byo"),
    /** Storage/retention LEVEL — snapshotted rows at rest; gated behind {@code tessary.metering.storage-enabled}. */
    STORAGE("storage");

    /**
     * The hourly bucket grain. The FINEST grain the worker produces and therefore the only one a total
     * over an arbitrary window may sum: a day row covers the same producer rows as the 24 hour rows beside
     * it, so any read that does not pin a grain double-counts.
     */
    public static final String BUCKET_HOUR = "hour";

    /** The daily bucket grain — the same producer rows re-aggregated over a whole closed day. */
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

    /**
     * The units the metering worker actually produces — everything but the reserved {@link #STORAGE} and
     * the four Track A retired. A retired unit is still a valid {@link #fromWire} value (history reads
     * back) but nothing writes a new row under it.
     */
    public static UsageUnit[] metered() {
        return new UsageUnit[] {INGESTED_SPANS, L1_EVALS};
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
