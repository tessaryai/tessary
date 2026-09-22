// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.CallSiteShapeReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.pipeline.CallSiteFact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Groundedness built-in: an output-vs-source correctness check no other built-in covers. Secret
 * Leak and Malformed Output score a structural fact and Frustration scores the user's reaction —
 * none of them compare the output against what it was supposed to be grounded in, so a
 * confidently-wrong-but-well-formed answer sails through untouched. This detector closes that gap:
 * {@link EncoderScorer#scoreResponses} hands the {@code groundedness} TOKEN head every retrieved
 * document and the whole answer in one pass, and the head marks the answer's unsupported words; the
 * detector fires on the strongest unsupported sentence.
 *
 * <p><b>The contract is "unsupported", not "contradicts" (v5).</b> The score is P(BASELESS) +
 * P(CONFLICT): an answer fires when it contradicts the evidence OR asserts what the evidence never
 * says. The earlier contradiction-only contract was measured on human-labelled RAG output (RAGTruth)
 * and found unreachable by any model of this size — 0.04-0.24 recall at a 2% false-alarm rate, the
 * previous pair head and the published checkpoints alike — while "unsupported" is what the annotators
 * labelled and where this head reaches 0.56 sentence recall at 2% FP and 0.66 response-level F1,
 * equal to the best published model. {@code conflict} rides along in {@code evidence_json} for a
 * reader who wants the narrower question. The audit is in the open tree's
 * {@code classifiers/groundedness/README.md}.
 *
 * <p><b>The passages are the evidence, or the prompt when there is none.</b> On a {@code rag_answer}
 * call site the source is the retrieved documents, which are not in the prompt; {@link
 * GroundingEvidenceReads} supplies them as a LIST, one per retrieved row, and they reach the head as
 * numbered passages — the layout the checkpoint was trained on, which a joined string cannot recover.
 * Only when a turn produced no evidence does the premise fall back to {@link
 * SubstrateObservation#groundingPremiseText()} — system AND user text, the document-in-prompt case
 * where the prompt genuinely IS the source — sent as one passage with no question, so the head sees
 * its summary layout rather than a question it would otherwise duplicate.
 *
 * <p><b>Tool-backed turns.</b> A tool result is not collected as evidence (it is JSON, not prose), so
 * a tool-backed {@code rag_answer} lands in the BLIND branch below and is abstained — an
 * {@code extract}/{@code summarize} turn keeps its prompt premise (see {@link
 * #EVIDENCE_EXPECTED_SHAPES}). Under the "unsupported" contract a true fact from a tool call that is
 * absent from the retrieved documents IS a finding when the turn also retrieved something; the
 * remedy is to pass tool output through as evidence, not to exempt it here.
 */
public final class GroundednessDetector implements BuiltInDetector {

    /**
     * Call-site shapes where the input plausibly carries verifiable source content (see
     * {@code CallSite}'s {@code shape} enum). Deliberately excludes {@code draft}/{@code agent_step}/
     * {@code conversational_turn}/etc. — open generation, no source to be ungrounded from.
     */
    static final Set<String> GROUNDED_SHAPES = Set.of("summarize", "extract", "rag_answer");

    /**
     * The shapes whose source material lives OUTSIDE the prompt, and which therefore have nothing to
     * fall back on when a trace reached outside and captured nothing readable.
     *
     * <p>{@code extract} and {@code summarize} are deliberately absent: for those the prompt IS the
     * document by definition of the shape, so a tool span elsewhere in the trace must not cost them
     * the premise they already had. Abstaining on every grounded shape would silence a
     * document-in-prompt turn merely for sharing a trace with a tool call.
     */
    private static final Set<String> EVIDENCE_EXPECTED_SHAPES = Set.of("rag_answer");

    /** Cap on the claim echoed into {@code evidence_json} — a detection is a pointer, not a payload. */
    private static final int CLAIM_ECHO_CHARS = 300;

    // P(unsupported) bands: HIGH confidence at or above threshold_high, LOW between the two, quiet
    // below threshold_low. The defaults are the catalog's v5 band — 0.975 is the 2% false-alarm point
    // on RAGTruth test (recall 0.41, precision 0.83), 0.5 the F1-optimal point (F1 0.66) — read with
    // thresholds cross-validated by response, never fitted to the scored set.
    private static final double DEFAULT_THRESHOLD_HIGH = 0.975;
    private static final double DEFAULT_THRESHOLD_LOW = 0.5;

    private final EncoderScorer scorer;
    private final CallSiteShapeReads shapes;
    private final GroundingEvidenceReads evidenceReads;
    private final ObjectMapper mapper;

    public GroundednessDetector(
            EncoderScorer scorer,
            CallSiteShapeReads shapes,
            GroundingEvidenceReads evidenceReads,
            ObjectMapper mapper) {
        this.scorer = scorer;
        this.shapes = shapes;
        this.evidenceReads = evidenceReads;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return Kind.GROUNDEDNESS;
    }

    /**
     * Gated on the call site's declared shape: outside {@link #GROUNDED_SHAPES} — and on a call site
     * with no shape at all — every observation is skipped. A shape arriving (or moving into the set)
     * therefore invalidates everything already swept — see {@link BuiltInDetector#callSiteFactsRead}.
     */
    @Override
    public Set<CallSiteFact> callSiteFactsRead() {
        return Set.of(CallSiteFact.SHAPE);
    }

    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        return detectBatch(List.of(obs), config).get(0);
    }

    @Override
    public List<Detection> detectBatch(List<SubstrateObservation> batch, @Nullable String config) {
        List<Detection> out = new ArrayList<>(batch.size());
        Set<String> siteIds = new HashSet<>();
        for (SubstrateObservation o : batch) {
            out.add(Detection.none());
            if (o.callSiteId() != null) siteIds.add(o.callSiteId());
        }
        if (siteIds.isEmpty()) return out;

        Map<String, String> shapeBySite = shapes.callSiteShapes(batch.get(0).projectId(), siteIds);
        if (shapeBySite.isEmpty()) return out;

        ConfigShape shape = parse(config);
        Double hi = shape == null ? null : shape.thresholdHigh();
        Double lo = shape == null ? null : shape.thresholdLow();
        double high = hi != null ? hi : DEFAULT_THRESHOLD_HIGH;
        double low = lo != null ? lo : DEFAULT_THRESHOLD_LOW;

        // Paired (trace, span) subjects: a v2 span id addresses a row only alongside its trace.
        Set<GroundingEvidenceReads.SpanRef> scorable = new HashSet<>();
        for (SubstrateObservation o : batch) {
            String sh = o.callSiteId() == null ? null : shapeBySite.get(o.callSiteId());
            if (sh != null && GROUNDED_SHAPES.contains(sh)) {
                scorable.add(new GroundingEvidenceReads.SpanRef(o.traceId(), o.observationId()));
            }
        }
        Map<String, GroundingEvidenceReads.Evidence> evidenceById = scorable.isEmpty()
                ? Map.of()
                : evidenceReads.groundingEvidence(batch.get(0).projectId(), scorable);

        List<EncoderScorer.Response> responses = new ArrayList<>();
        List<Integer> responseIndex = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            SubstrateObservation o = batch.get(i);
            String siteShape = o.callSiteId() == null ? null : shapeBySite.get(o.callSiteId());
            if (siteShape == null || !GROUNDED_SHAPES.contains(siteShape)) continue;
            String answer = o.outputText();
            if (answer.isBlank()) continue;
            // An answer that asserts nothing checkable never reaches the head: measured on real
            // turns with no evidence, greetings and questions were 48 of 54 false firings. See
            // VerifiableClaims. The head scores the WHOLE answer; this is a gate, not a splitter.
            if (VerifiableClaims.of(answer).isEmpty()) continue;
            GroundingEvidenceReads.Evidence ev =
                    evidenceById.getOrDefault(o.observationId(), new GroundingEvidenceReads.Evidence(List.of(), false));
            List<String> documents = ev.documents();
            // ABSTAIN only when BLIND on a shape that HAS no prompt fallback — the conversation reached
            // outside and captured nothing readable, so the answer's source is not ours to judge.
            // Not abstained: a conversation that reached outside for nothing (the prompt is the whole
            // world), and extract/summarize, whose document is in the prompt.
            if (documents.isEmpty()
                    && ev.conversationDidExternalWork()
                    && EVIDENCE_EXPECTED_SHAPES.contains(siteShape)) {
                continue;
            }
            List<String> passages;
            String question;
            if (!documents.isEmpty()) {
                passages = documents;
                String q = o.inputText();
                question = q.isBlank() ? null : q;
            } else {
                String premise = o.groundingPremiseText();
                if (premise.isBlank()) continue;
                passages = List.of(premise);
                question = null;
            }
            responses.add(new EncoderScorer.Response(passages, question, answer));
            responseIndex.add(i);
        }
        if (responses.isEmpty()) return out;

        List<EncoderScorer.ResponseScore> scores = scorer.scoreResponses("groundedness", responses);
        for (int r = 0; r < scores.size(); r++) {
            EncoderScorer.ResponseScore rs = scores.get(r);
            if (!rs.scored()) continue; // refused by the encoder (too long): no verdict, not "clean"
            double unsupported = rs.unsupported();
            if (unsupported < low) continue;
            String confidence = unsupported >= high ? Detection.Confidence.HIGH : Detection.Confidence.LOW;
            int obsIndex = responseIndex.get(r);
            SubstrateObservation scored = batch.get(obsIndex);
            boolean hadEvidence = !evidenceById
                    .getOrDefault(scored.observationId(), new GroundingEvidenceReads.Evidence(List.of(), false))
                    .documents()
                    .isEmpty();
            out.set(
                    obsIndex,
                    Detection.fired(
                            Detection.Severity.WARN,
                            evidence(rs, hadEvidence, responses.get(r).answer()),
                            confidence));
        }
        return out;
    }

    /**
     * {@code premise_had_evidence} is on every firing so a reader can tell what was actually compared;
     * {@code claim} is the worst-scoring sentence, cut from the answer by the head's own offsets, which
     * is the difference between "this answer is 0.98 unsupported" and a finding someone can act on.
     * {@code conflict} is the narrower question's score, so a consumer that only cares about outright
     * contradiction can filter on it without a second model.
     */
    private String evidence(EncoderScorer.ResponseScore rs, boolean premiseHadEvidence, String answer) {
        EncoderScorer.Span worst = null;
        for (EncoderScorer.Span sp : rs.spans()) {
            if (worst == null || sp.unsupported() > worst.unsupported()) worst = sp;
        }
        String claim = worst == null
                ? answer
                : answer.substring(
                        Math.max(0, Math.min(worst.start(), answer.length())),
                        Math.max(0, Math.min(worst.end(), answer.length())));
        try {
            return mapper.writeValueAsString(Map.of(
                    "head",
                    "groundedness",
                    "unsupported",
                    round(rs.unsupported()),
                    "conflict",
                    round(rs.conflict()),
                    "premise_had_evidence",
                    premiseHadEvidence,
                    "sentences",
                    rs.spans().size(),
                    "claim",
                    claim.length() > CLAIM_ECHO_CHARS ? claim.substring(0, CLAIM_ECHO_CHARS) : claim));
        } catch (JsonProcessingException e) {
            return "{\"head\":\"groundedness\"}";
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
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
            Double thresholdLow) {}
}
