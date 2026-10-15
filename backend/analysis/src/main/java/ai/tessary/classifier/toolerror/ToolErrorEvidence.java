// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorRate.Pattern;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Keeps {@code MetricFindingEvidence}'s conventions: round before serializing, omit an empty block, and never let a
 * reported number be re-derived elsewhere. Contract: {@code devdocs/concepts/tool-error.md} §6-7.
 */
public final class ToolErrorEvidence {

    private ToolErrorEvidence() {}

    static final ObjectMapper JSON = new ObjectMapper();

    /** Never renamed; the first segment of every cause key. */
    public static final String MEASURE = "tool_error_rate";

    /** Absent on blobs where the counts spanned the tool's whole history. */
    public static final String COUNTS_BASIS_ONSET = "onset";

    /** Legible because people paste it into queries. No reference segment: a CUSUM has one reference. */
    public static String causeKey(String bucketKey, Decision decision) {
        return MEASURE + ":" + bucketKey + ":" + decision.direction().wire();
    }

    /**
     * Built from the accumulator, not effect size: the ranked list is a work queue, and what should rise is what has
     * done the most damage.
     */
    public static double severityOf(double criticality) {
        double c = Math.max(0.0, criticality);
        return Math.min(1.0, c / (c + 60.0));
    }

    public static String toJson(
            String bucketKey,
            Decision decision,
            List<Pattern> patterns,
            boolean patternsTruncated,
            List<String> traceIds,
            @Nullable String windowOpenedAt,
            @Nullable String windowClosedAt) {
        ObjectNode root = JSON.createObjectNode();
        root.put("measure", MEASURE);
        ObjectNode bucket = root.putObject("bucket");
        bucket.put("kind", ToolErrorBuckets.KIND);
        bucket.put("key", bucketKey);
        root.put("direction", decision.direction().wire());
        root.put("statistic", round(decision.statistic(), 3));
        root.put("threshold", round(decision.threshold(), 3));
        root.put("criticality", round(decision.criticality(), 1));
        root.put("effect_size", round(decision.effectSize(), 4));
        root.put("delta_pp", round(decision.deltaPp(), 3));

        // Stated, never inferred: absorbing pins these counts, and guessing the basis wrong would pin a lifetime
        // average or a tiny burst as the normal. Absence means the blob predates the change.
        root.put("counts_basis", COUNTS_BASIS_ONSET);
        ObjectNode rate = root.putObject("rate");
        rate.put("ref", round(decision.baselineRate(), 6));
        rate.put("cur", round(decision.currentRate(), 6));
        root.put("n_ref", decision.baselineCalls());
        root.put("n_cur", decision.callsSinceOnset());
        ObjectNode failures = root.putObject("failures");
        failures.put("cur", decision.failuresSinceOnset());

        // Ranked by what changed. Omitted when empty, since an empty array is noise to a triage agent.
        if (!patterns.isEmpty()) {
            ArrayNode arr = root.putArray("patterns");
            for (Pattern p : patterns) {
                ObjectNode n = arr.addObject();
                n.put("signature", p.signature());
                n.put("source", p.source());
                n.put("ref", p.ref());
                n.put("cur", p.cur());
            }
            // A truncated list must never read as the whole story.
            if (patternsTruncated) root.put("patterns_truncated", true);
        }

        // Lets a reader, or Layer 2, read instances rather than take the rate on trust. Omitted when empty.
        if (!traceIds.isEmpty()) {
            ArrayNode arr = root.putArray("failing_traces");
            for (String traceId : traceIds) {
                arr.add(traceId);
            }
        }

        if (decision.onsetAt() != null) root.put("onset_at", decision.onsetAt());
        ObjectNode window = root.putObject("window");
        if (windowOpenedAt != null) window.put("opened_at", windowOpenedAt);
        if (windowClosedAt != null) window.put("closed_at", windowClosedAt);
        window.put("kind", "recomputed");

        return root.toString();
    }

    /** Null rather than zeroed, so a caller can say "numbers unavailable" instead of fabricating them. */
    /**
     * @param countsAreOnsetRun false on older blobs, where absorption refuses rather than pin a reference the human did
     * not agree to @param criticality 0 on a blob that predates the metric
     */
    public record Read(
            String bucketKey,
            double refRate,
            double curRate,
            double deltaPp,
            int patternCount,
            long nCur,
            long failuresCur,
            boolean countsAreOnsetRun,
            double criticality) {}

    public static @Nullable Read read(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(json);
            String bucketKey = root.path("bucket").path("key").asText("");
            if (bucketKey.isEmpty()) return null;
            return new Read(
                    bucketKey,
                    root.path("rate").path("ref").asDouble(0),
                    root.path("rate").path("cur").asDouble(0),
                    root.path("delta_pp").asDouble(0),
                    root.path("patterns").size(),
                    root.path("n_cur").asLong(0),
                    root.path("failures").path("cur").asLong(0),
                    COUNTS_BASIS_ONSET.equals(root.path("counts_basis").asText("")),
                    root.path("criticality").asDouble(0));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * One signature going from rare to common is the tool breaking; an even rise across old signatures is usually
     * traffic.
     */
    public record PatternShift(String signature, String source, long ref, long cur) {}

    public record RateDetail(
            String bucketKey,
            double refRate,
            double curRate,
            double deltaPp,
            long nRef,
            long nCur,
            long failuresCur,
            List<PatternShift> patterns,
            boolean patternsTruncated,
            List<String> failingTraces,
            @Nullable String onsetAt,
            @Nullable String windowOpenedAt,
            @Nullable String windowClosedAt,
            /** Never inferred from the rates. */
            String direction,
            double statistic,
            double threshold,
            double effectSize,
            double criticality) {}

    public static @Nullable RateDetail detail(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(json);
            String bucketKey = root.path("bucket").path("key").asText("");
            if (bucketKey.isEmpty()) return null;
            List<PatternShift> patterns = new ArrayList<>();
            for (JsonNode n : root.path("patterns")) {
                patterns.add(new PatternShift(
                        n.path("signature").asText(""),
                        n.path("source").asText(""),
                        n.path("ref").asLong(0),
                        n.path("cur").asLong(0)));
            }
            JsonNode window = root.path("window");
            return new RateDetail(
                    bucketKey,
                    root.path("rate").path("ref").asDouble(0),
                    root.path("rate").path("cur").asDouble(0),
                    root.path("delta_pp").asDouble(0),
                    root.path("n_ref").asLong(0),
                    root.path("n_cur").asLong(0),
                    root.path("failures").path("cur").asLong(0),
                    List.copyOf(patterns),
                    root.path("patterns_truncated").asBoolean(false),
                    failingTraces(json),
                    text(root.path("onset_at")),
                    text(window.path("opened_at")),
                    text(window.path("closed_at")),
                    root.path("direction").asText("up"),
                    root.path("statistic").asDouble(0),
                    root.path("threshold").asDouble(0),
                    root.path("effect_size").asDouble(0),
                    root.path("criticality").asDouble(0));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static @Nullable String text(JsonNode node) {
        String s = node.asText("");
        return s.isEmpty() ? null : s;
    }

    /**
     * Read here because {@code failing_traces} is written in this class, and a persisted shape whose writer and reader
     * live apart is a bug nothing type-checks.
     */
    public static List<String> failingTraces(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode node : JSON.readTree(json).path("failing_traces")) {
                String id = node.asText("");
                if (!id.isEmpty()) out.add(id);
            }
            return List.copyOf(out);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public static String shortName(String bucketKey) {
        int colon = bucketKey.indexOf(':');
        return colon >= 0 && colon + 1 < bucketKey.length() ? bucketKey.substring(colon + 1) : bucketKey;
    }

    private static double round(double v, int places) {
        if (!Double.isFinite(v)) return 0.0;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
