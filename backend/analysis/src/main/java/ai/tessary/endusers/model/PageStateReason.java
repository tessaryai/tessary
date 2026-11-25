// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Why the page has its state (spec 3.5). The frontend words each code with the reason numbers of the snapshot.
 * {@code low_id_share} reads the share across all history, {@code low_id_share_28} the share of the last 28 days.
 */
public enum PageStateReason implements WireCode {
    LOW_ID_SHARE("low_id_share"),
    LOW_ID_SHARE_28("low_id_share_28"),
    SHORT_HISTORY("short_history"),
    FEW_IDS("few_ids"),
    RECENT_ID_CHANGE("recent_id_change"),
    HEAVY_IDS("heavy_ids"),
    IDS_REPLACED("ids_replaced"),
    IDS_NOT_SEEN_AGAIN("ids_not_seen_again"),
    IDS_COPY_SESSION_TOKEN("ids_copy_session_token"),
    IDS_SET_ASIDE("ids_set_aside"),
    ID_LOSS("id_loss"),
    ID_CHANGE("id_change"),
    SOME_IDS_REPLACED("some_ids_replaced");

    private final String wire;

    PageStateReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static PageStateReason fromWire(String value) {
        return WireCode.parse(PageStateReason.class, value);
    }
}
