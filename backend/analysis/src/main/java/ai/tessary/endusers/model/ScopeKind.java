// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** The scope of a shared silence or ID change event: all IDs, IDs of one shape group, or IDs of one account cluster. */
public enum ScopeKind implements WireCode {
    APP("app"),
    SHAPE("shape"),
    CLUSTER("cluster");

    private final String wire;

    ScopeKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }
}
