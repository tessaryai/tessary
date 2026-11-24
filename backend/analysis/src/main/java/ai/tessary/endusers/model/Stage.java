// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A funnel stage of a covered user ID. Held, set-aside and cannot-follow IDs have none. The codes equal the {@code end_user.stage} check constraint. */
public enum Stage implements WireCode {
    ACTIVE("active"),
    SILENT("silent"),
    STOPPED("stopped");

    private final String wire;

    Stage(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Stage fromWire(String value) {
        return WireCode.parse(Stage.class, value);
    }
}
