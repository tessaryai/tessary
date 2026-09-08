// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import ai.tessary.evals.classifier.ClassifierField;
import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.evals.classifier.substrate.ConversationThreadAssembler.ContextPolicy;
import ai.tessary.evals.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An encoder-classifier detector: the semantic tier of the built-in catalog. The observation's
 * assembled conversation thread ({@link ConversationThreadAssembler}, {@link ClassifierField}-selected
 * side) is scored by one of the standalone classify-service's ONNX
 * encoder heads ({@link EncoderScorer} → classify-service {@code /classify}), and the score is
 * banded into the discovery-vs-tracking confidence split: {@code score ≥ threshold_high} fires
 * HIGH (surfaces in either mode), {@code ≥ threshold_low} fires LOW (discovery only), below stays
 * quiet.
 *
 * <p>{@link #detectBatch} is the real evaluation path — the whole sweep batch goes to the head in
 * ONE call (blank texts are skipped without a call). Per-project {@code config_json}
 * ({@code {"threshold_high":0.9,"threshold_low":0.7}}) overrides the baked operating point.
 */
public final class EncoderDetector implements BuiltInDetector {

    private static final double DEFAULT_THRESHOLD_HIGH = 0.9;
    private static final double DEFAULT_THRESHOLD_LOW = 0.7;

    private final String kind;
    private final String head;
    private final ClassifierField field;
    private final String severity;
    private final EncoderScorer scorer;
    private final ObjectMapper mapper;
    private final ConversationThreadAssembler threadAssembler;

    public EncoderDetector(
            String kind,
            String head,
            ClassifierField field,
            String severity,
            EncoderScorer scorer,
            ObjectMapper mapper,
            ConversationThreadAssembler threadAssembler) {
        this.kind = kind;
        this.head = head;
        this.field = field;
        this.severity = severity;
        this.scorer = scorer;
        this.mapper = mapper;
        this.threadAssembler = threadAssembler;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        return detectBatch(List.of(obs), config).get(0);
    }

    @Override
    public List<Detection> detectBatch(List<SubstrateObservation> batch, @Nullable String config) {
        ConfigShape shape = parse(config);
        Double hi = shape == null ? null : shape.thresholdHigh();
        Double lo = shape == null ? null : shape.thresholdLow();
        double high = hi != null ? hi : DEFAULT_THRESHOLD_HIGH;
        double low = lo != null ? lo : DEFAULT_THRESHOLD_LOW;
        ContextPolicy policy = contextPolicy(shape);

        List<Detection> out = new ArrayList<>(batch.size());
        List<String> texts = new ArrayList<>();
        List<Integer> textIndex = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            out.add(Detection.none());
            String text = threadAssembler.assemble(batch.get(i), field, policy);
            if (!text.isBlank()) {
                texts.add(text);
                textIndex.add(i);
            }
        }
        if (texts.isEmpty()) return out;

        List<Double> scores = scorer.score(head, texts);

        // The ATTRIBUTION GATE. This head answers "is there negative affect"; on a 884-turn census
        // only 21% of its HIGH fires are frustration the AGENT caused — the rest is aimed at the
        // restaurant, the courier, a promo code or a billing bug. A second head scores causation and
        // demotes the ones it cannot attribute to the agent.
        //
        // DEMOTES, does not drop. A gated-out turn falls HIGH -> LOW rather than disappearing, so
        // discovery mode (the high+low union) still sees everything it saw before, the decision stays
        // visible in `evidence`, and turning the gate off is a config change rather than a backfill.
        // Only TRACKING mode, which reads HIGH alone, feels the difference — which is exactly the
        // queue the gate was measured on.
        String gateHead = shape == null ? null : shape.attributionHead();
        Double gateThr = shape == null ? null : shape.attributionThreshold();
        Map<Integer, Double> gateScores = Map.of();
        if (gateHead != null && !gateHead.isBlank() && gateThr != null) {
            List<Integer> candidates = new ArrayList<>();
            for (int t = 0; t < scores.size(); t++) {
                if (scores.get(t) >= high) candidates.add(t);
            }
            if (!candidates.isEmpty()) {
                // The gate reads the FULL thread — real assistant prose and tool markers — not the
                // narrowed, assistant-stubbed string this head is configured for. It has to see what
                // the agent actually did in order to judge whether the agent caused anything, and it
                // was trained and measured on exactly that shape. Assembling it separately is the
                // point, not an inefficiency.
                List<String> gateTexts = new ArrayList<>(candidates.size());
                for (int t : candidates) {
                    gateTexts.add(threadAssembler.assemble(batch.get(textIndex.get(t)), field, ContextPolicy.FULL));
                }
                List<Double> gs = scorer.score(gateHead, gateTexts);
                Map<Integer, Double> collected = new java.util.HashMap<>();
                for (int i = 0; i < candidates.size() && i < gs.size(); i++) {
                    collected.put(candidates.get(i), gs.get(i));
                }
                gateScores = collected;
            }
        }

        for (int t = 0; t < scores.size(); t++) {
            double score = scores.get(t);
            if (score < low) continue;
            String confidence = score >= high ? Detection.Confidence.HIGH : Detection.Confidence.LOW;
            Double gate = gateScores.get(t);
            if (gate != null && gateThr != null && gate < gateThr) {
                confidence = Detection.Confidence.LOW; // demoted: affect is real, attribution is not
            }
            out.set(textIndex.get(t), Detection.fired(severity, evidence(score, gateHead, gate), confidence));
        }
        return out;
    }

    private String evidence(double score, @Nullable String gateHead, @Nullable Double gateScore) {
        double rounded = Math.round(score * 1000) / 1000.0;
        try {
            if (gateHead == null || gateScore == null) {
                return mapper.writeValueAsString(Map.of("head", head, "score", rounded));
            }
            // Both scores are recorded. The review UI already parses this blob, and ordering the
            // queue by attribution_score needs no migration because the per-classifier detection tables
            // already project `evidence` unchanged.
            return mapper.writeValueAsString(Map.of(
                    "head", head,
                    "score", rounded,
                    "attribution_head", gateHead,
                    "attribution_score", Math.round(gateScore * 1000) / 1000.0));
        } catch (JsonProcessingException e) {
            return "{\"head\":\"" + head + "\"}";
        }
    }

    /**
     * The context shape this head wants. Absent keys mean {@link ContextPolicy#FULL} — the whole
     * thread with assistant prose intact — so a head that says nothing keeps exactly the behaviour it
     * had before the policy existed, and only a head that opts in is narrowed.
     */
    private static ContextPolicy contextPolicy(@Nullable ConfigShape shape) {
        if (shape == null) return ContextPolicy.FULL;
        Integer userTurns = shape.contextUserTurns();
        Boolean stubAssistant = shape.contextStubAssistant();
        int window = userTurns != null ? userTurns : ContextPolicy.FULL.userTurnWindow();
        boolean stub = stubAssistant != null ? stubAssistant : ContextPolicy.FULL.stubAssistant();
        Integer minPriorTurns = shape.contextMinPriorUserTurns();
        int minPrior = minPriorTurns != null ? minPriorTurns : ContextPolicy.FULL.minPriorUserTurns();
        return new ContextPolicy(window, stub, minPrior);
    }

    @Nullable
    private ConfigShape parse(@Nullable String config) {
        if (config == null || config.isBlank()) return null;
        try {
            return mapper.readValue(config, ConfigShape.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * The keys this detector owns. {@code ignoreUnknown} is load-bearing, not politeness: a classifier's
     * {@code config_json} is one blob shared with features that key off it too (the pre-deploy loop
     * reads {@code surfaces} from it), and the platform mapper is a bare {@code new ObjectMapper()}
     * with {@code FAIL_ON_UNKNOWN_PROPERTIES} left ON. Without this, one foreign key makes {@link
     * #parse} throw, the catch returns null, and the detector silently falls back to its baked
     * defaults — a signal that reads as configured while ignoring its configuration.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfigShape(
            @com.fasterxml.jackson.annotation.JsonProperty("threshold_high") @Nullable
            Double thresholdHigh,

            @com.fasterxml.jackson.annotation.JsonProperty("threshold_low") @Nullable
            Double thresholdLow,

            @com.fasterxml.jackson.annotation.JsonProperty("context_user_turns") @Nullable
            Integer contextUserTurns,

            @com.fasterxml.jackson.annotation.JsonProperty("context_stub_assistant") @Nullable
            Boolean contextStubAssistant,

            @com.fasterxml.jackson.annotation.JsonProperty("context_min_prior_user_turns") @Nullable
            Integer contextMinPriorUserTurns,

            /** Head name for the attribution gate, e.g. {@code attribution}. Absent = gate off. */
            @com.fasterxml.jackson.annotation.JsonProperty("attribution_head") @Nullable
            String attributionHead,

            /** Score at or above which the gate lets a HIGH fire through. Absent = gate off. */
            @com.fasterxml.jackson.annotation.JsonProperty("attribution_threshold") @Nullable
            Double attributionThreshold) {}
}
