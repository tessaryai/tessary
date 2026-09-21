// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.finding.FindingRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The summary block for a classifier whose only detail is what {@link ClassifierArming} already wrote
 * to the finding's own payload: frustration, groundedness, and any regex/threshold classifier. Secret
 * Leak is the one classifier {@link ClassifierArming} facets, and its richer, DB-backed detail lives
 * in {@code SecretLeakDetailService} instead — this is the summary for every OTHER arming classifier,
 * which has nothing beyond the bar it crossed.
 */
public final class ArmedWindowEvidence {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ArmedWindowEvidence() {}

    /**
     * The bar a classifier-wide or per-facet arming crossed: what was counted, over how long, and
     * against what threshold — {@link ClassifierArming#payload}'s whole shape.
     */
    public record ArmedWindowDetail(
            /** {@code event_count} or {@code distinct_users}: what {@link #observed} counts. */
            String basis,
            long observed,
            long threshold,
            long windowSeconds,
            @Nullable String windowStart,
            @Nullable String windowEnd,
            /** {@code high} when this window held a high-band detection, {@code low} otherwise, or null
             *  for a classifier with no confidence banding. */
            @Nullable String confidence) {}

    /**
     * Parse the blob for the detail surface, or null when it is not an armed-window payload or cannot
     * be read.
     */
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

    /**
     * The one sentence the Classifiers page and the case both carry for a classifier-wide arming:
     * {@code "groundedness fired on 3 detections in 1 d, bar 3"}. Facet-level armings (secret leak)
     * have their own title, built from the facet and call site instead.
     */
    public static String title(String classifierKey, ArmedWindowDetail bar) {
        return String.format(
                Locale.ROOT,
                "%s fired on %d %s in %s, bar %d",
                classifierKey,
                bar.observed(),
                unit(bar.basis()),
                windowText(bar.windowSeconds()),
                bar.threshold());
    }

    /** "detections" or "users": the noun the basis counts. */
    public static String unit(String basis) {
        return "distinct_users".equals(basis) ? "users" : "detections";
    }

    /** {@code 86400} → {@code 1 d}; {@code 3600} → {@code 1 h}; {@code 900} → {@code 15 min}; else seconds. */
    public static String windowText(long windowSeconds) {
        if (windowSeconds > 0 && windowSeconds % 86_400 == 0) return (windowSeconds / 86_400) + " d";
        if (windowSeconds > 0 && windowSeconds % 3_600 == 0) return (windowSeconds / 3_600) + " h";
        if (windowSeconds > 0 && windowSeconds % 60 == 0) return (windowSeconds / 60) + " min";
        return windowSeconds + " s";
    }

    private static @Nullable String text(JsonNode node) {
        String s = node.asText("");
        return s.isEmpty() ? null : s;
    }
}
