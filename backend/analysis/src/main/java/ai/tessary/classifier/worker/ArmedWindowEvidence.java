// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.finding.FindingRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Summary block for arming classifiers whose only detail is the bar {@link ClassifierArming} wrote to the finding
 * payload. Secret Leak's richer detail lives in {@code SecretLeakDetailService}.
 */
public final class ArmedWindowEvidence {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ArmedWindowEvidence() {}

    /** {@link ClassifierArming#payload}'s whole shape. */
    public record ArmedWindowDetail(
            /** What {@link #observed} counts. */
            String basis,
            long observed,
            long threshold,
            long windowSeconds,
            @Nullable String windowStart,
            @Nullable String windowEnd,
            /** Null for a classifier with no confidence banding. */
            @Nullable String confidence) {}

    /** Null when it is not a readable armed-window payload. */
    public static @Nullable ArmedWindowDetail detail(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(json);
            if (!FindingRow.Cause.ARMED_WINDOW.equals(root.path("cause_kind").asText(""))) return null;
            return new ArmedWindowDetail(
                    root.path("basis").asText("event_count"),
                    root.path("observed").asLong(0),
                    root.path("threshold").asLong(0),
                    root.path("window_seconds").asLong(0),
                    text(root.path("window_start")),
                    text(root.path("window_end")),
                    text(root.path(FindingRow.Confidence.PAYLOAD_KEY)));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static @Nullable String text(JsonNode node) {
        String s = node.asText("");
        return s.isEmpty() ? null : s;
    }
}
