// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which days make an ID's day series (spec 2): paced days of a person, paced days under the lenient fallback, automation days, or every day. */
public enum ActivityBasis implements WireCode {
    HUMAN("human"),
    LENIENT("lenient"),
    UNATTENDED("unattended"),
    MACHINE("machine");

    private final String wire;

    ActivityBasis(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ActivityBasis fromWire(String value) {
        return WireCode.parse(ActivityBasis.class, value);
    }
}
