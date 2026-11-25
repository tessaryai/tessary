// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What the Probably stopped stage may show, by the first rule that applies (level 2 withholding rule); {@code cannot_be_named_yet} is the full state with the notice of spec 7.2. */
public enum StopStageState implements WireCode {
    HISTORY_TOO_SHORT("history_too_short"),
    NOT_JUDGED_YET("not_judged_yet"),
    CANNOT_TELL_YET("cannot_tell_yet"),
    MIXED_ID_FORMATS("mixed_id_formats"),
    EXPECTED_ONLY("expected_only"),
    CANNOT_BE_NAMED_YET("cannot_be_named_yet"),
    EXPECTED_AND_NAMED("expected_and_named");

    private final String wire;

    StopStageState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static StopStageState fromWire(String value) {
        return WireCode.parse(StopStageState.class, value);
    }
}
