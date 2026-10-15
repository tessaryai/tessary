// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Parses a finding's {@code payload} and {@code evidence_counts} for both {@link FindingRow} and {@link FindingClaim},
 * so the two never disagree about a malformed payload.
 */
final class FindingPayload {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FindingPayload() {}

    /** An empty object when absent or malformed: the finding is still true. */
    static JsonNode tree(@Nullable String json) {
        if (json == null || json.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    static @Nullable String text(@Nullable String json, String key) {
        JsonNode node = tree(json).path(key);
        return node.isTextual() ? node.asText() : null;
    }

    /** 0 for a role the detector wrote none under. */
    static long count(@Nullable String countsJson, String role) {
        JsonNode node = tree(countsJson).path(role);
        return node.isNumber() ? node.asLong() : 0;
    }
}
