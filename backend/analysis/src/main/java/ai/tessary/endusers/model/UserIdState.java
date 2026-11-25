// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The display state of a user ID (spec 7.2 without the {@code at_risk} and {@code slowing} rows); {@code unknown} on a refused page. */
public enum UserIdState implements WireCode {
    STOPPED("stopped"),
    NEW("new"),
    ACTIVE("active"),
    UNKNOWN("unknown");

    private final String wire;

    UserIdState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static UserIdState fromWire(String value) {
        return WireCode.parse(UserIdState.class, value);
    }
}
