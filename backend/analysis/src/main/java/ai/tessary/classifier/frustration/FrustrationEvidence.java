// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A {@code frustration_rate} finding's payload, turned into tool_error's {@link RateDetail} so the same rate figure
 * renders it.
 */
public final class FrustrationEvidence {

    public static final String MEASURE = "frustration_rate";

    /** Never goes through triage. */
    public static final String SUMMARY = "Frustrated sessions at this call site rose above its own learned"
            + " rate. Opened without triage; the numbers are on the finding.";

    private FrustrationEvidence() {}

    /**
     * @param jevThreshold the per-turn score above which a turn was flagged @param conversationsNextCursor null when
     * this is all of them
     */
    public record FrustrationDetail(
            RateDetail rate,
            long baselineFrustrated,
            @Nullable String scorerVersion,
            double jevThreshold,
            long arlTarget,
            double minDecisionInterval,
            List<FrustratedConversationView> conversations,
            @Nullable String conversationsNextCursor) {

        /** No session or trace ids, for the agent door. */
        public FrustrationDetail withoutIds() {
            return new FrustrationDetail(
                    rate,
                    baselineFrustrated,
                    scorerVersion,
                    jevThreshold,
                    arlTarget,
                    minDecisionInterval,
                    List.of(),
                    null);
        }
    }

    public record FrustratedSessionPage(
            List<FrustratedConversationView> rows,
            long total,
            @Nullable String nextCursor) {

        public FrustratedSessionPage {
            rows = List.copyOf(rows);
        }
    }

    /**
     * @param contextTraceIds up to {@link #CONTEXT_TURNS_BEFORE} turns before the flagged one, then the flagged trace,
     * oldest first @param message clipped; null once retention cleared it
     */
    public record FrustratedConversationView(
            String conversationId,
            String traceId,
            @Nullable Double score,
            @Nullable String callSiteId,
            @Nullable String flaggedAt,
            boolean cleared,
            @Nullable String sessionId,
            List<String> contextTraceIds,
            @Nullable String message) {

        public FrustratedConversationView {
            contextTraceIds = List.copyOf(contextTraceIds);
        }
    }

    public static final int CONTEXT_TURNS_BEFORE = 2;

    /** {@code cause_kind} is added by the finding writer. */
    public static String payload(
            ObjectMapper mapper, String callSite, Decision d, long baselineFrustrated, FrustrationConfig config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("call_site_id", callSite);
        body.put("direction", "up");
        body.put("baseline_conversations", d.baselineCalls());
        body.put("baseline_frustrated", baselineFrustrated);
        body.put("baseline_rate", d.baselineRate());
        body.put("current_rate", d.currentRate());
        body.put("conversations_since_onset", d.callsSinceOnset());
        body.put("frustrated_since_onset", d.failuresSinceOnset());
        body.put("delta_pp", d.deltaPp());
        body.put("effect_size", d.effectSize());
        body.put("statistic", d.statistic());
        body.put("threshold", d.threshold());
        body.put("criticality", d.criticality());
        if (d.onsetAt() != null) body.put("onset_at", d.onsetAt());
        body.put("arl_target", config.arlTarget());
        body.put("min_decision_interval", config.minDecisionInterval());
        body.put("scorer_version", config.scorerVersion());
        body.put("jev_threshold", config.threshold());
        return mapper.valueToTree(body).toString();
    }

    public static RateDetail rateDetail(FindingRow finding) {
        JsonNode body = finding.payload();
        return new RateDetail(
                finding.callSiteId() == null ? "" : finding.callSiteId(),
                body.path("baseline_rate").asDouble(0),
                body.path("current_rate").asDouble(0),
                body.path("delta_pp").asDouble(0),
                body.path("baseline_conversations").asLong(0),
                body.path("conversations_since_onset").asLong(0),
                body.path("frustrated_since_onset").asLong(0),
                List.of(),
                false,
                List.of(),
                finding.onsetAt(),
                null,
                null,
                body.path("direction").asText("up"),
                body.path("statistic").asDouble(0),
                body.path("threshold").asDouble(0),
                body.path("effect_size").asDouble(0),
                body.path("criticality").asDouble(0));
    }

    public static FrustrationDetail detail(
            FindingRow finding, List<FrustratedConversationView> conversations, @Nullable String nextCursor) {
        JsonNode body = finding.payload();
        JsonNode version = body.path("scorer_version");
        return new FrustrationDetail(
                rateDetail(finding),
                body.path("baseline_frustrated").asLong(0),
                version.isTextual() ? version.asText() : null,
                body.path("jev_threshold").asDouble(0),
                body.path("arl_target").asLong(0),
                body.path("min_decision_interval").asDouble(0),
                List.copyOf(conversations),
                nextCursor);
    }

    public static double severity(FindingRow finding) {
        double criticality = finding.payload().path("criticality").asDouble(Double.NaN);
        return Double.isNaN(criticality) ? 0.0 : ToolErrorEvidence.severityOf(criticality);
    }
}
