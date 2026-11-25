// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Why a user ID is not covered (spec 3.3), first reason that applies. */
public enum SetAsideReason implements WireCode {
    AGGREGATE("aggregate"),
    SERVICE("service"),
    ODD_SHAPE("odd_shape"),
    TOKEN_LINK("token_link"),
    NON_RETURNING_GROUP("non_returning_group"),
    MACHINE_ONLY("machine_only");

    private final String wire;

    SetAsideReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static SetAsideReason fromWire(String value) {
        return WireCode.parse(SetAsideReason.class, value);
    }
}
