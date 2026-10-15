// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;
import java.time.Duration;
import java.util.Locale;

/**
 * Bucket width of the org LLM-usage timeseries. Its own vocabulary, not {@code UsageUnit}'s, because this series is
 * aggregated live off {@code llm_call} and can offer a week bucket the rollup table lacks.
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

    /** Also the {@code date_trunc} field name. */
    public String wire() {
        return wire;
    }

    /** A Postgres interval literal. */
    public String step() {
        return step;
    }

    /** Nominal: sizes only the bucket-count guard, never a boundary, which {@code date_trunc} computes. */
    public Duration width() {
        return width;
    }

    public static LlmUsageGrain fromWire(String value) {
        for (LlmUsageGrain g : values()) {
            if (g.wire.equals(value.toLowerCase(Locale.ROOT))) return g;
        }
        throw new TessaryException(MeteringError.UNKNOWN_SERIES_GRAIN, value);
    }
}
