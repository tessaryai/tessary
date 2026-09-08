// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.metering;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.MeteringError;
import java.time.Duration;
import java.util.Locale;

/**
 * The bucket width of the org LLM-usage timeseries — the "time break-up" the usage page offers next to
 * its window. Deliberately its own vocabulary rather than {@code UsageUnit}'s hour/day rollup grains:
 * this series is aggregated live off {@code llm_call} with {@code date_trunc}, so it can offer a week
 * bucket the closed-bucket rollup table has no rows for.
 */
public enum LlmUsageGrain {
    HOUR("hour", "1 hour", Duration.ofHours(1)),
    DAY("day", "1 day", Duration.ofDays(1)),
    WEEK("week", "7 days", Duration.ofDays(7));

    private final String wire;
    private final String step;
    private final Duration width;

    LlmUsageGrain(String wire, String step, Duration width) {
        this.wire = wire;
        this.step = step;
        this.width = width;
    }

    /** The wire value, which is also the {@code date_trunc} field name Postgres expects. */
    public String wire() {
        return wire;
    }

    /** The {@code generate_series} step for this grain, as a Postgres interval literal. */
    public String step() {
        return step;
    }

    /**
     * The bucket's nominal width. Week is exactly 7 days and day exactly 24 h here — the value only
     * sizes the "how many buckets would this window produce" guard, never a bucket boundary (those come
     * from {@code date_trunc}, which handles the calendar).
     */
    public Duration width() {
        return width;
    }

    public static LlmUsageGrain fromWire(String value) {
        for (LlmUsageGrain g : values()) {
            if (g.wire.equals(value.toLowerCase(Locale.ROOT))) return g;
        }
        throw new EvalsException(MeteringError.UNKNOWN_SERIES_GRAIN, value);
    }
}
