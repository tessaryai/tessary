// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A limit line under the funnel (spec 9 and level 2). Which lines are never cut is the judge's rule, not the code's. */
public enum LimitCode implements WireCode {
    GROUP_OR_KEY_IDS("group_or_key_ids"),
    TRACER_CANNOT_TEST("tracer_cannot_test"),
    FEW_COVERED_IDS("few_covered_ids"),
    CANNOT_BE_NAMED_YET("cannot_be_named_yet"),
    NAMED_RIGHT_LOW("named_right_low"),
    NAMED_FOUND_LOW("named_found_low"),
    NO_ANCHOR("no_anchor"),
    LENIENT_BASIS("lenient_basis"),
    UNATTENDED_SHARE("unattended_share"),
    ID_LOSS("id_loss"),
    ID_CHANGE("id_change"),
    SHARED_SILENCE("shared_silence"),
    ONE_DAY_SHARE("one_day_share"),
    ACCOUNT_EVENT("account_event"),
    RETURN_CYCLE_TOO_LONG("return_cycle_too_long"),
    REPEAT_CONTACT("repeat_contact"),
    FACTS_NOT_KEPT("facts_not_kept"),
    IMPORTS_OUTSIDE_WINDOW("imports_outside_window");

    private final String wire;

    LimitCode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static LimitCode fromWire(String value) {
        return WireCode.parse(LimitCode.class, value);
    }
}
