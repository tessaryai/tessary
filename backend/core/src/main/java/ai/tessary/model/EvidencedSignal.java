// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The label accepts several aliases because product_profile sections name it differently (kind, regime, signal). */
public record EvidencedSignal(String label, String evidence) {

    public EvidencedSignal {
        if (evidence == null) evidence = "";
    }

    @JsonCreator
    public static EvidencedSignal create(
            @JsonProperty("label") @JsonAlias({"kind", "regime", "signal"}) String label,
            @JsonProperty("evidence") String evidence) {
        return new EvidencedSignal(label, evidence);
    }
}
