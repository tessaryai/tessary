// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The detail of an active user ID (spec 7.2): held as a possible shared break, or overdue ({@code 0.3 <= p_stop < 0.6}). */
public enum Substate implements WireCode {
    HELD("held"),
    OVERDUE("overdue");

    private final String wire;

    Substate(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Substate fromWire(String value) {
        return WireCode.parse(Substate.class, value);
    }
}
