// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;
import java.util.Locale;

/**
 * Group-by axis of the org LLM-usage timeseries; {@link #NONE} gives the org total. The wire vocabulary only, so an
 * unknown value is a 422 before any query is built.
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
        throw new TessaryException(MeteringError.UNKNOWN_GROUPING, value);
    }
}
