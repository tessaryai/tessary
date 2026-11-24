// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The grade of a stop (spec 7.2). */
public enum Grade implements WireCode {
    STOPPED("stopped"),
    PROBABLY_STOPPED("probably_stopped"),
    UNATTENDED("unattended"),
    NOT_YET_CHECKED("not_yet_checked");

    private final String wire;

    Grade(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Grade fromWire(String value) {
        return WireCode.parse(Grade.class, value);
    }
}
