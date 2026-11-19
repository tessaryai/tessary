// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A prompt's shape, fixed at creation: a text body is one string, a chat body is a list of messages. */
public enum PromptType {
    TEXT("text"),
    CHAT("chat");

    private final String wire;

    PromptType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** Exact match, no trimming or case folding: the wire form is also the stored {@code prompt.type}. */
    @JsonCreator
    public static PromptType fromWire(String value) {
        for (PromptType t : values()) {
            if (t.wire.equals(value)) return t;
        }
        throw new TessaryException(CommonError.INVALID_PARAMETER, "type");
    }
}
