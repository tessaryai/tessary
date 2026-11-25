// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.model.WireCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a count of user IDs hit counts: every distinct user ID value before the first snapshot, only covered user IDs
 * after it, and nothing for a metric shift with no stored hit row. A metric shift with hit rows counts like every other
 * case.
 */
public enum CountKind implements WireCode {
    ID_VALUES("id_values"),
    COVERED("covered"),
    NOT_APPLICABLE("not_applicable");

    private final String wire;

    CountKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static CountKind fromWire(String value) {
        return WireCode.parse(CountKind.class, value);
    }
}
