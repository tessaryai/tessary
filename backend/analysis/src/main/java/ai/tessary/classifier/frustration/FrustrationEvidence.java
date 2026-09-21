// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code frustration_rate} finding says about itself, written by {@link FrustrationRateService} and read
 * back by the finding page and the case source. Mirrors {@code MalformedOutputEvidence}: a flat payload in
 * conversations, turned into tool_error's {@link RateDetail} so the same rate figure renders it.
 */
public final class FrustrationEvidence {

    /** The {@code eval_case} tuple's measure segment for a frustration case. */
    public static final String MEASURE = "frustration_rate";

    /** The ruling every spell's finding is filed with. It never goes through triage. */
    public static final String SUMMARY = "Frustrated conversations at this call site rose above its own learned"
            + " rate. Opened without triage; the numbers are on the finding.";

    private FrustrationEvidence() {}

    /**
     * The finding page's frustration block: the rate since onset against the learned rate, the tuning it was
     * judged under, and the conversations it cites with the turn that fired in each.
     *
     * @param jevThreshold the per-turn score above which a turn was flagged
     */
    public record FrustrationDetail(
            RateDetail rate,
            long baselineFrustrated,
            @Nullable String scorerVersion,
            double jevThreshold,
            long arlTarget,
            double minDecisionInterval,
            List<FrustratedConversationView> conversations) {

        /** The same block with no conversation or trace ids, for the agent door. */
        public FrustrationDetail withoutIds() {
            return new FrustrationDetail(
                    rate, baselineFrustrated, scorerVersion, jevThreshold, arlTarget, minDecisionInterval, List.of());
        }
    }

    /**
     * One frustrated conversation the finding cites.
     *
     * @param callSiteId the flagged turn's own call site, which can differ from the finding's: a conversation
     *     counts on the call site of its first scored turn
     * @param cleared true once a {@code false_alarm} resolve cleared the conversation's flag
     */
    public record FrustratedConversationView(
            String conversationId,
            String traceId,
            @Nullable Double score,
            @Nullable String callSiteId,
            @Nullable String flaggedAt,
            boolean cleared) {}

    /** The payload a spell's finding carries. {@code cause_kind} is added by the finding writer. */
    static String payload(
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
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"call_site_id\":\"" + callSite + "\"}";
        }
    }

    /** The rate, read off the finding's own flat payload, in the shape tool_error's figure renders. */
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

    /** The page block for a finding, with the conversations the caller read for it. */
    public static FrustrationDetail detail(FindingRow finding, List<FrustratedConversationView> conversations) {
        JsonNode body = finding.payload();
        JsonNode version = body.path("scorer_version");
        return new FrustrationDetail(
                rateDetail(finding),
                body.path("baseline_frustrated").asLong(0),
                version.isTextual() ? version.asText() : null,
                body.path("jev_threshold").asDouble(0),
                body.path("arl_target").asLong(0),
                body.path("min_decision_interval").asDouble(0),
                List.copyOf(conversations));
    }

    /** Ordering weight for a case, the squash every rate detector on tool_error's engine shares. */
    public static double severity(FindingRow finding) {
        double criticality = finding.payload().path("criticality").asDouble(Double.NaN);
        return Double.isNaN(criticality) ? 0.0 : ToolErrorEvidence.severityOf(criticality);
    }
}
