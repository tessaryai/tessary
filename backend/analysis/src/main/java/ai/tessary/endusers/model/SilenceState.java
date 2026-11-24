// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The state of a shared silence event (spec 4). */
public enum SilenceState implements WireCode {
    OPEN("open"),
    PROVEN_BREAK("proven_break"),
    NOT_A_BREAK("not_a_break"),
    TRAFFIC_ENDED("traffic_ended");

    private final String wire;

    SilenceState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static SilenceState fromWire(String value) {
        return WireCode.parse(SilenceState.class, value);
    }
}
