// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ProductProfile(
        @Nullable String domain,
        @JsonProperty("user_types") List<UserType> userTypes,
        @JsonProperty("business_model") @Nullable String businessModel,
        @JsonProperty("data_sensitivity") List<EvidencedSignal> dataSensitivity,
        @JsonProperty("regulatory_context") List<EvidencedSignal> regulatoryContext,
        @JsonProperty("brand_voice_signals") List<EvidencedSignal> brandVoiceSignals,
        @JsonProperty("notable_dependencies") List<String> notableDependencies) {
    public ProductProfile {
        userTypes = userTypes == null ? List.of() : userTypes;
        dataSensitivity = dataSensitivity == null ? List.of() : dataSensitivity;
        regulatoryContext = regulatoryContext == null ? List.of() : regulatoryContext;
        brandVoiceSignals = brandVoiceSignals == null ? List.of() : brandVoiceSignals;
        notableDependencies = notableDependencies == null ? List.of() : notableDependencies;
    }

    public record UserType(String role, String surface, String constraints, String evidence) {}
}
