// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The gap stratum of a silence (spec 5): one series day, two or three, four or more, or automation running while the person is silent. */
public enum Stratum implements WireCode {
    K1("K1"),
    K2("K2"),
    E("E"),
    AU("AU");

    private final String wire;

    Stratum(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Stratum fromWire(String value) {
        return WireCode.parse(Stratum.class, value);
    }
}
