// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.metering;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.MeteringError;
import java.util.Locale;

/**
 * The axis the org LLM-usage timeseries is cut by — the "group by" of the usage chart. {@link #NONE}
 * yields one series (the org total per bucket); the rest yield one series per lane / project / model.
 *
 * <p>The SQL each grouping expands to lives in {@code LlmUsageQueryRepository}, not here: this enum is the
 * wire vocabulary and the validation seam, so an unknown value is a 422 before any query is built.
 */
public enum LlmUsageGrouping {
    NONE("none"),
    LANE("lane"),
    PROJECT("project"),
    MODEL("model");

    private final String wire;

    LlmUsageGrouping(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static LlmUsageGrouping fromWire(String value) {
        for (LlmUsageGrouping g : values()) {
            if (g.wire.equals(value.toLowerCase(Locale.ROOT))) return g;
        }
        throw new EvalsException(MeteringError.UNKNOWN_GROUPING, value);
    }
}
