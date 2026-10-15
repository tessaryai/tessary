// SPDX-License-Identifier: Apache-2.0
package ai.tessary.gate;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public final class PreDeployCheckDtos {

    private PreDeployCheckDtos() {}

    public record PreDeployCheckView(
            String id,
            @JsonProperty("classifier_id") String classifierId,
            String surface,
            @JsonProperty("failure_mode_id") @Nullable String failureModeId,
            String intensity,
            String status,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {

        public static PreDeployCheckView of(PreDeployCheckRow r) {
            return new PreDeployCheckView(
                    r.id(),
                    r.classifierId(),
                    r.surface(),
                    r.failureModeId(),
                    r.intensity(),
                    r.status(),
                    r.createdAt(),
                    r.updatedAt());
        }
    }
}
