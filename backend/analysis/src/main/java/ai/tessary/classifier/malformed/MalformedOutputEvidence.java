// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire shapes of Malformed Output's "How outputs broke" block and their pure builders; the DB-backed half is
 * {@link MalformedOutputDetailService}.
 */
public final class MalformedOutputEvidence {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The {@code eval_case} tuple's last segment. */
    public static final String MEASURE = "malformed_output_rate";

    private MalformedOutputEvidence() {}

    public record SchemaFieldView(String path, String name, String type, boolean required, int depth, long failing) {}

    /**
     * @param notJson outputs that did not parse as JSON @param other outputs whose violations predate structured
     * violations, so the field cannot be recovered (forward-only, no backfill)
     */
    public record MalformedDetail(
            ToolErrorEvidence.RateDetail rate, List<SchemaFieldView> fields, long notJson, long other) {}

    public record FailingOutputView(
            String traceId,
            String spanId,
            @Nullable String name,
            String startedAt,
            @Nullable String document,
            List<Integer> highlightLines,
            @Nullable String message) {}

    public record FailingOutputPage(
            List<FailingOutputView> rows,
            long total,
            @Nullable String nextCursor) {}

    /**
     * Built from the flat payload, since {@link ToolErrorEvidence#detail} expects tool_error's nested shape. No pattern
     * breakdown: the schema tree is that breakdown.
     */
    public static ToolErrorEvidence.RateDetail rateDetail(FindingRow finding, List<String> failingTraces) {
        JsonNode body = finding.payload();
        String bucketKey = finding.callSiteId() == null ? "" : finding.callSiteId();
        return new ToolErrorEvidence.RateDetail(
                bucketKey,
                body.path("baseline_rate").asDouble(0),
                body.path("current_rate").asDouble(0),
                body.path("delta_pp").asDouble(0),
                body.path("baseline_calls").asLong(0),
                body.path("calls_since_onset").asLong(0),
                body.path("failures_since_onset").asLong(0),
                List.of(),
                false,
                failingTraces,
                finding.onsetAt(),
                null,
                null,
                body.path("direction").asText("up"),
                body.path("statistic").asDouble(0),
                body.path("threshold").asDouble(0),
                body.path("effect_size").asDouble(0),
                body.path("criticality").asDouble(0));
    }

    /** Null for a bucket with no single message to show. */
    static @Nullable String messageForField(@Nullable String evidenceJson, String field) {
        if (evidenceJson == null || evidenceJson.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(evidenceJson);
            for (JsonNode v : root.path("violations")) {
                if (v.isObject() && field.equals(v.path("field").asText(""))) {
                    return v.path("message").asText(null);
                }
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
