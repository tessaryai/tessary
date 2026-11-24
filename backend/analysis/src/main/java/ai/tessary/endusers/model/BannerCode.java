// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A page banner, one per fact (spec 9 and the level 2 outage guard). Declaration order is display order. */
public enum BannerCode implements WireCode {
    PAGE_REFUSAL("page_refusal"),
    GROUP_OR_KEY_IDS("group_or_key_ids"),
    ID_LOSS("id_loss"),
    ID_CHANGE("id_change"),
    TRACER("tracer"),
    SHARED_SILENCE("shared_silence"),
    SEASON_LEVEL("season_level"),
    CANNOT_BE_NAMED_YET("cannot_be_named_yet"),
    OUTAGE("outage");

    private final String wire;

    BannerCode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static BannerCode fromWire(String value) {
        return WireCode.parse(BannerCode.class, value);
    }
}
