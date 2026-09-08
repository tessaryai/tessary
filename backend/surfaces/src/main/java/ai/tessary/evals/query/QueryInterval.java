// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.query;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.QueryError;

/**
 * The allow-listed bucketing intervals for {@code timeseries()}. The wire value maps to a fixed
 * Postgres {@code date_trunc} unit — the unit is selected from this closed enum and never interpolated
 * from a raw request string, so {@code date_trunc(:unit, created_at::timestamptz)} stays injection-safe.
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

    /** The fixed {@code date_trunc} unit literal (a trusted constant, never user input). */
    public String truncUnit() {
        return truncUnit;
    }

    /** Resolve a wire interval name to the enum, or {@code 400} if unknown. */
    public static QueryInterval fromWire(String wire) {
        for (QueryInterval i : values()) {
            if (i.truncUnit.equals(wire)) {
                return i;
            }
        }
        throw new EvalsException(QueryError.UNKNOWN_INTERVAL, wire);
    }
}
