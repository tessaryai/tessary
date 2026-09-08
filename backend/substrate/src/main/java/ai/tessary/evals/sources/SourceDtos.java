// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.sources;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** HTTP-shaped DTOs for /api/sources. */
public final class SourceDtos {

    private SourceDtos() {}

    public record CreateSourceRequest(
            @NotBlank String provider,
            @NotBlank String name,
            @NotBlank String baseUrl,
            Map<String, String> credentials) {}

    public record SourceResponse(
            String id, String provider, String name, String baseUrl, String createdAt, String updatedAt) {
        public static SourceResponse of(SourceRow row) {
            return new SourceResponse(
                    row.id(), row.provider(), row.name(), row.baseUrl(), row.createdAt(), row.updatedAt());
        }
    }

    public record DeleteResponse(boolean deleted) {}
}
