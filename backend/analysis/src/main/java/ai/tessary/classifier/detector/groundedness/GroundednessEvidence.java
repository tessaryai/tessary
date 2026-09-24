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
import org.jspecify.annotations.Nullable;

/**
 * What a {@code groundedness_rate} finding says about itself, written by {@link GroundednessRateService} and
 * read back by the finding page and the case source. Mirrors {@code FrustrationEvidence}: a flat payload in
 * traces, turned into tool_error's {@link RateDetail} so the same rate figure renders it.
 */
public final class GroundednessEvidence {

    /** The {@code eval_case} tuple's measure segment for a groundedness case. */
    public static final String MEASURE = "groundedness_rate";

    private GroundednessEvidence() {}

    /**
     * The finding page's groundedness block: the flagged rate since onset against the learned rate, the tuning it
     * was judged under, and the first page of the flagged answers it cites.
     *
     * @param flagThreshold the sentence score at or above which an answer is flagged
     * @param baselineTraces the traces the learned rate was fitted on
     * @param learningUntil the baseline size at which the learned rate stops moving
     * @param answers the newest flagged answers, one page of them
     * @param answersNextCursor where the next page starts, or null when this is all of them
     */
    public record GroundednessDetail(
            RateDetail rate,
            double flagThreshold,
            long baselineTraces,
            long learningUntil,
            long arlTarget,
            List<FlaggedAnswerView> answers,
            @Nullable String answersNextCursor) {

        public GroundednessDetail {
            answers = List.copyOf(answers);
        }

        /** The same block with no answers, and so no trace or span ids, for the agent door. */
        public GroundednessDetail withoutIds() {
            return new GroundednessDetail(
                    rate, flagThreshold, baselineTraces, learningUntil, arlTarget, List.of(), null);
        }
    }

    /** One page of a finding's flagged answers, and how many it cites in all. */
    public record FlaggedAnswerPage(
            List<FlaggedAnswerView> rows,
            long total,
            @Nullable String nextCursor) {

        public FlaggedAnswerPage {
            rows = List.copyOf(rows);
        }
    }

    /**
     * One flagged answer the finding cites, read back as the model was sent it.
     *
     * @param sessionId the flagged trace's session, which the page links to; null when it carries none
     * @param flaggedAt when the flagged span started; null once it aged out
     * @param score the highest flagged sentence's score, or the answer's own when no sentence is recorded
     * @param question the user's text the answer replied to; null when the prompt was the passage, or when
     *     {@code stored} is false
     * @param answer the exact string that was scored, which {@code flaggedSentences} index into in UTF-16 code
     *     units; null when {@code stored} is false
     * @param flaggedSentences every sentence at or above the flag threshold, by start
     * @param documents what the answer was compared against: the retrieved documents, best rank first, or the
     *     prompt as one passage when {@code premiseHadEvidence} is false; null when {@code stored} is false
     * @param premiseHadEvidence whether {@code documents} are retrieved documents rather than the prompt
     * @param stored false once the trace's payload aged out, and with it the question, answer and documents
     * @param cleared true once a {@code false_alarm} resolve cleared the flag
     */
    public record FlaggedAnswerView(
            String traceId,
            String spanId,
            @Nullable String sessionId,
            @Nullable String flaggedAt,
            @Nullable Double score,
            @Nullable String question,
            @Nullable String answer,
            List<FlaggedSentenceView> flaggedSentences,
            @Nullable List<RetrievedDocumentView> documents,
            boolean premiseHadEvidence,
            boolean stored,
            boolean cleared) {

        public FlaggedAnswerView {
            flaggedSentences = List.copyOf(flaggedSentences);
            documents = documents == null ? null : List.copyOf(documents);
        }
    }

    /** One flagged sentence: {@code [start, end)} in the answer, in UTF-16 code units, and its score. */
    public record FlaggedSentenceView(int start, int end, double score) {}

    /**
     * One passage the answer was compared against.
     *
     * @param title the retrieved row's source or name; null while the evidence read carries none, which the page
     *     shows as a numbered document
     */
    public record RetrievedDocumentView(@Nullable String title, String text) {}

    /** The page block for a finding, with the first page of answers the caller read for it. */
    public static GroundednessDetail detail(
            FindingRow finding, List<FlaggedAnswerView> answers, @Nullable String nextCursor) {
        JsonNode body = finding.payload();
        return new GroundednessDetail(
                rateDetail(finding),
                body.path("flag_threshold").asDouble(GroundednessConfig.DEFAULT_THRESHOLD),
                body.path("baseline_traces").asLong(0),
                body.path("learning_until").asLong(0),
                body.path("arl_target").asLong(0),
                answers,
                nextCursor);
    }

    /** The payload a spell's finding carries. {@code cause_kind} is added by the finding writer. */
    public static String payload(
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
