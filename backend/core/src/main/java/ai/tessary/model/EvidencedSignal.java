// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A label + supporting file-path evidence. The label field has multiple
 * accepted aliases because different sections of the product_profile use
 * different names for it (kind / regime / signal).
 */
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
