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
 * The wire shapes of Malformed Output's "How outputs broke" block, and the pure (no-repository)
 * parts of building them — mirrors {@code ToolErrorEvidence} and {@code MetricFindingEvidence}: the
 * cause-specific detail record lives beside the classifier that fills it, and {@code BehaviorDtos}
 * only imports it.
 *
 * <p>The DB-backed half — the schema read, the per-field failure counts, the failing-output pages —
 * is {@link MalformedOutputDetailService}, since unlike a tool-error or metric-drift blob this one
 * cannot be rebuilt from the finding's own payload alone.
 */
public final class MalformedOutputEvidence {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MalformedOutputEvidence() {}

    /** One field of the declared schema, flattened and annotated with its own failure count. */
    public record SchemaFieldView(String path, String name, String type, boolean required, int depth, long failing) {}

    /**
     * The whole block: the rate in the same shape a tool-error finding renders it, the schema tree, and
     * the two buckets no declared field owns.
     *
     * @param notJson outputs that did not parse as JSON at all
     * @param other outputs whose violations predate the structured-violation rework, so which field
     *     they hit cannot be recovered (decision: forward-only detail, no backfill)
     */
    public record MalformedDetail(
            ToolErrorEvidence.RateDetail rate, List<SchemaFieldView> fields, long notJson, long other) {}

    /** One failing output, for the field a reader selected. */
    public record FailingOutputView(
            String traceId,
            String spanId,
            @Nullable String name,
            String startedAt,
            @Nullable String document,
            List<Integer> highlightLines,
            @Nullable String message) {}

    /** A page of {@link FailingOutputView}s, the field's population size, and the cursor past this page. */
    public record FailingOutputPage(
            List<FailingOutputView> rows,
            long total,
            @Nullable String nextCursor) {}

    /**
     * {@link ToolErrorEvidence.RateDetail} built from a {@code malformed_rate} finding's own flat
     * payload ({@link ai.tessary.classifier.malformed.MalformedOutputRateService#payload}) rather than
     * parsed back with {@link ToolErrorEvidence#detail}: that reader expects tool_error's nested {@code
     * bucket}/{@code rate} shape, and this classifier's payload was never written in it.
     *
     * <p>Carries no pattern breakdown — this classifier's failure signatures are schema fields, and the
     * schema tree is that breakdown — and its failing traces are the finding's own WITNESS evidence
     * rather than a {@code failing_traces} payload key this classifier never wrote.
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
                null);
    }

    /**
     * The message a detection's own evidence carries for one field: the first structured violation
     * whose {@code field} matches, or null for a bucket ({@code not_json} / {@code other} / a field an
     * old-shaped blob cannot name) with no single message to show.
     */
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
