// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;

/**
 * Allow-listed {@code timeseries()} intervals. The {@code date_trunc} unit comes from this closed enum, never from the
 * request string, so the query stays injection-safe.
 */
public enum QueryInterval {
    HOUR("hour"),
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    private final String truncUnit;

    QueryInterval(String truncUnit) {
        this.truncUnit = truncUnit;
    }

    /** A trusted constant, never user input. */
    public String truncUnit() {
        return truncUnit;
    }

    /** 400 if unknown. */
    public static QueryInterval fromWire(String wire) {
        for (QueryInterval i : values()) {
            if (i.truncUnit.equals(wire)) {
                return i;
            }
        }
        throw new TessaryException(QueryError.UNKNOWN_INTERVAL, wire);
    }
}
