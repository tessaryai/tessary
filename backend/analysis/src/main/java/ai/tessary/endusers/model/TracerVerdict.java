// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The tracer verdict (spec 3.6). */
public enum TracerVerdict implements WireCode {
    IDS_REPLACED("ids_replaced"),
    SOME_IDS_REPLACED("some_ids_replaced"),
    NO_CHANGE_SEEN("no_change_seen"),
    CANNOT_TEST("cannot_test");

    private final String wire;

    TracerVerdict(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static TracerVerdict fromWire(String value) {
        return WireCode.parse(TracerVerdict.class, value);
    }
}
