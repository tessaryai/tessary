// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What the Users page can show for a project, by the first rule of spec 3.5 that applies; {@code collecting} is shown before the first snapshot and {@code not_computed} above the size budget. */
public enum PageState implements WireCode {
    OK("ok"),
    PARTIAL_COVERAGE("partial_coverage"),
    TOO_EARLY("too_early"),
    NO_USER_ID("no_user_id"),
    IDS_UNRELIABLE("ids_unreliable"),
    NOT_COMPUTED("not_computed"),
    COLLECTING("collecting");

    private final String wire;

    PageState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static PageState fromWire(String value) {
        return WireCode.parse(PageState.class, value);
    }
}
