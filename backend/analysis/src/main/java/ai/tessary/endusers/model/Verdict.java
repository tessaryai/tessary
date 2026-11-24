// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The verdict of an issue (spec 8.7 and the level 2 guards), in the order the rules are tried. The codes equal the {@code end_user_issue.verdict} check constraint; {@code not_applicable} is a metric shift, which has no hit user IDs. */
public enum Verdict implements WireCode {
    PAGE_REFUSED("page_refused"),
    HITS_NOT_COMPLETE("hits_not_complete"),
    HISTORY_TOO_SHORT("history_too_short"),
    CANNOT_TELL_YET("cannot_tell_yet"),
    CANNOT_MEASURE_YET("cannot_measure_yet"),
    TOO_FEW_IDS("too_few_ids"),
    REACHES_NEARLY_ALL("reaches_nearly_all"),
    NOT_COMPARABLE("not_comparable"),
    ALWAYS_PRESENT("always_present"),
    FEW_ACCOUNTS("few_accounts"),
    EVIDENCE_OF_LOSS("evidence_of_loss"),
    MOVED_NOT_ADDED("moved_not_added"),
    NO_MEASURABLE_LOSS("no_measurable_loss"),
    NOT_ENOUGH_EVIDENCE("not_enough_evidence"),
    NOT_APPLICABLE("not_applicable");

    private final String wire;

    Verdict(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Verdict fromWire(String value) {
        return WireCode.parse(Verdict.class, value);
    }
}
