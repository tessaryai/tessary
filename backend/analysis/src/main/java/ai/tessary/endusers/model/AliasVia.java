// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What links two user IDs of one person (spec 3.2). The codes equal the {@code end_user_alias_edge.via} check constraint. */
public enum AliasVia implements WireCode {
    SESSION("session"),
    THREAD("thread");

    private final String wire;

    AliasVia(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static AliasVia fromWire(String value) {
        return WireCode.parse(AliasVia.class, value);
    }
}
