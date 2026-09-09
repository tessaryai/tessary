// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The two JSON blobs a finding carries — {@code payload} and {@code evidence_counts} — read the way
 * every projection of the row reads them.
 *
 * <p>Shared rather than copied because there is now more than one projection: {@link FindingRow} is the
 * whole row and {@link FindingClaim} is the claim without the rulings, and a second copy of the parse is
 * how the two would come to disagree about a malformed payload.
 */
final class FindingPayload {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FindingPayload() {}

    /** The blob as a tree; an empty object when absent or malformed — a finding is still true. */
    static JsonNode tree(@Nullable String json) {
        if (json == null || json.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    /** A top-level string member, or null when absent or unreadable. */
    static @Nullable String text(@Nullable String json, String key) {
        JsonNode node = tree(json).path(key);
        return node.isTextual() ? node.asText() : null;
    }

    /** A role's ref count off {@code evidence_counts}, 0 for a role the detector wrote none under. */
    static long count(@Nullable String countsJson, String role) {
        JsonNode node = tree(countsJson).path(role);
        return node.isNumber() ? node.asLong() : 0;
    }
}
