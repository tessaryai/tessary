// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

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

/**
 * What a {@code groundedness_rate} finding says about itself, written by {@link GroundednessRateService} and
 * read back by the case source. Mirrors {@code FrustrationEvidence}: a flat payload in traces, turned into
 * tool_error's {@link RateDetail} so the same rate figure renders it.
 */
public final class GroundednessEvidence {

    /** The {@code eval_case} tuple's measure segment for a groundedness case. */
    public static final String MEASURE = "groundedness_rate";

    private GroundednessEvidence() {}

    /** The payload a spell's finding carries. {@code cause_kind} is added by the finding writer. */
    static String payload(
            ObjectMapper mapper, String callSite, Decision d, long baselineFlagged, GroundednessConfig config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("call_site_id", callSite);
        body.put("direction", "up");
        body.put("baseline_traces", d.baselineCalls());
        body.put("baseline_flagged", baselineFlagged);
        body.put("baseline_rate", d.baselineRate());
        body.put("current_rate", d.currentRate());
        body.put("traces_since_onset", d.callsSinceOnset());
        body.put("flagged_since_onset", d.failuresSinceOnset());
        body.put("delta_pp", d.deltaPp());
        body.put("effect_size", d.effectSize());
        body.put("statistic", d.statistic());
        body.put("threshold", d.threshold());
        body.put("criticality", d.criticality());
        if (d.onsetAt() != null) body.put("onset_at", d.onsetAt());
        body.put("arl_target", config.arlTarget());
        body.put("min_decision_interval", config.minDecisionInterval());
        body.put("scorer_version", config.scorerVersion());
        body.put("flag_threshold", config.threshold());
        body.put("learning_until", config.freezeBaselineTraces());
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
                body.path("baseline_traces").asLong(0),
                body.path("traces_since_onset").asLong(0),
                body.path("flagged_since_onset").asLong(0),
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

    /** Ordering weight for a case, the squash every rate detector on tool_error's engine shares. */
    public static double severity(FindingRow finding) {
        double criticality = finding.payload().path("criticality").asDouble(Double.NaN);
        return Double.isNaN(criticality) ? 0.0 : ToolErrorEvidence.severityOf(criticality);
    }
}
