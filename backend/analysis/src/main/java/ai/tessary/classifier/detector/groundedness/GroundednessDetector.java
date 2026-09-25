// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.CallSiteShapeReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.pipeline.CallSiteFact;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * a tool-backed {@code rag_answer} lands in {@link GroundednessInputs}' BLIND branch and is abstained —
 * an {@code extract}/{@code summarize} turn keeps its prompt premise. Under the "unsupported"
 * contract a true fact from a tool call that is absent from the retrieved documents IS a finding when
 * the turn also retrieved something; the remedy is to pass tool output through as evidence, not to
 * exempt it here.
 *
 * <p><b>One threshold, and every score counted (v7).</b> An answer is flagged when its strongest
 * sentence reaches {@code threshold}; there is no review band. The sweep writes one {@code
 * groundedness_assessment} row for every answer the model scored, flagged or not, because the rate
 * test that files findings needs the answers that passed as much as the ones that did not. A flagged
 * answer's detection lists every sentence at or above the threshold, with its offsets.
 */
public final class GroundednessDetector implements BuiltInDetector {

    /** Cap on the claim echoed into {@code evidence_json} — a detection is a pointer, not a payload. */
    private static final int CLAIM_ECHO_CHARS = 300;

    /** The served model, and the revision {@code classifiers/groundedness/serve.py} pins by default. */
    static final String MODEL = "tessaryai/groundedness-classifier-v1@6746fa25f4f6cdb60f994f056c1919300e6c2b12";

    /**
     * What the model is sent, as {@link GroundednessInputs} builds it: retrieved documents as numbered
     * passages with the question, else the prompt as one passage. A change to that layout is a change
     * to what a score means, so it is part of {@link #scorerVersion}.
     */
    static final String ENCODING = "passages-question-answer-v1";

    private final EncoderScorer scorer;
    private final GroundednessInputs inputs;
    private final GroundednessAssessmentRepository assessments;
    private final ObjectMapper mapper;

    public GroundednessDetector(
            EncoderScorer scorer,
            CallSiteShapeReads shapes,
            GroundingEvidenceReads evidenceReads,
            GroundednessAssessmentRepository assessments,
            ObjectMapper mapper) {
        this.scorer = scorer;
        this.inputs = new GroundednessInputs(shapes, evidenceReads);
        this.assessments = assessments;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return Kind.GROUNDEDNESS;
    }

    /**
     * Gated on the call site's declared shape: outside {@link GroundednessInputs#GROUNDED_SHAPES} — and
     * on a call site with no shape at all — every observation is skipped. A shape arriving (or moving
     * into the set) therefore invalidates everything already swept — see {@link
     * BuiltInDetector#callSiteFactsRead}.
     */
    @Override
    public Set<CallSiteFact> callSiteFactsRead() {
        return Set.of(CallSiteFact.SHAPE);
    }

    /** Groundedness scores only through {@link #sweepBatch}, which records each trial it scores. */
    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        throw new UnsupportedOperationException("groundedness scores only through sweepBatch");
    }

    /**
     * The batch's detections, and one {@code groundedness_assessment} row per answer the model scored,
     * flagged or not: the trials the rate test counts. An answer the inputs skip, or the encoder
     * refused, writes nothing, because it is not a trial.
     */
    @Override
    public List<Detection> sweepBatch(ClassifierRow signal, List<SubstrateObservation> batch, @Nullable String config) {
        return score(signal, batch, config);
    }

    /**
     * What produced an assessment: a hash of the model and its revision, the input layout and the
     * threshold, since a flag under one of them and under another are different events. A change to
     * any of them starts a new set of assessment rows beside the old ones.
     */
    public static String scorerVersion(double threshold) {
        String material = MODEL + "|" + ENCODING + "|" + String.format(Locale.ROOT, "%.4f", threshold);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "gnd-v1-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash the groundedness scorer", e);
        }
    }

    /**
     * The flag cutoff {@code config} sets, as {@link GroundednessConfig} reads it: {@code threshold}, else an
     * older blob's {@code threshold_high}, else {@link GroundednessConfig#DEFAULT_THRESHOLD}.
     */
    double threshold(@Nullable String config) {
        return GroundednessConfig.of(mapper, config).threshold();
    }

    private List<Detection> score(ClassifierRow signal, List<SubstrateObservation> batch, @Nullable String config) {
        List<Detection> out = new ArrayList<>(Collections.nCopies(batch.size(), Detection.none()));
        List<GroundednessInputs.@Nullable Inputs> read = inputs.read(batch);

        List<GroundednessInputs.Inputs> sent = new ArrayList<>();
        List<EncoderScorer.Response> responses = new ArrayList<>();
        List<Integer> responseIndex = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            GroundednessInputs.Inputs in = read.get(i);
            if (in == null) continue;
            sent.add(in);
            responses.add(new EncoderScorer.Response(in.passages(), in.question(), in.answer()));
            responseIndex.add(i);
        }
        if (responses.isEmpty()) return out;

        double threshold = threshold(config);
        String scorerVersion = scorerVersion(threshold);
        List<EncoderScorer.ResponseScore> scores = scorer.scoreResponses("groundedness", responses);
        for (int r = 0; r < scores.size(); r++) {
            EncoderScorer.ResponseScore rs = scores.get(r);
            if (!rs.scored()) continue; // refused by the encoder (too long): no verdict, not "clean"
            int obsIndex = responseIndex.get(r);
            SubstrateObservation scored = batch.get(obsIndex);
            boolean flagged = rs.unsupported() >= threshold;
            assessments.insert(new GroundednessAssessmentRepository.Assessment(
                    Ids.ulid(),
                    scored.projectId(),
                    signal.id(),
                    scored.sessionId(),
                    scored.traceId(),
                    scored.observationId(),
                    scored.callSiteId() == null ? "" : scored.callSiteId(),
                    rs.unsupported(),
                    flagged,
                    scorerVersion,
                    scored.createdAt()));
            if (!flagged) continue;
            GroundednessInputs.Inputs in = sent.get(r);
            out.set(
                    obsIndex,
                    Detection.fired(
                            Detection.Severity.WARN,
                            evidence(rs, in.premiseHadEvidence(), in.answer(), threshold),
                            Detection.Confidence.HIGH));
        }
        return out;
    }

    /**
     * {@code flagged_sentences} is every sentence at or above the threshold, by start, with offsets into
     * the answer that was scored; the page marks them in the answer. The offsets are UTF-16 code units,
     * the unit Java and the browser index strings by; the model returns code points and they are
     * converted here. {@code claim} is the worst sentence's text, kept for readers of the older shape.
     * {@code premise_had_evidence} says what the answer was actually compared against, and {@code
     * conflict} is the narrower question's score, so a consumer that only cares about outright
     * contradiction can filter on it without a second model.
     */
    private String evidence(
            EncoderScorer.ResponseScore rs, boolean premiseHadEvidence, String answer, double threshold) {
        EncoderScorer.Span worst = null;
        List<EncoderScorer.Span> flagged = new ArrayList<>();
        for (EncoderScorer.Span sp : rs.spans()) {
            if (worst == null || sp.unsupported() > worst.unsupported()) worst = sp;
            if (sp.unsupported() >= threshold) flagged.add(sp);
        }
        flagged.sort(Comparator.comparingInt(EncoderScorer.Span::start));
        List<Map<String, Object>> sentences = new ArrayList<>(flagged.size());
        for (EncoderScorer.Span sp : flagged) {
            Map<String, Object> sentence = new LinkedHashMap<>();
            sentence.put("start", utf16(answer, sp.start()));
            sentence.put("end", utf16(answer, sp.end()));
            sentence.put("unsupported", round(sp.unsupported()));
            sentences.add(sentence);
        }
        String claim = answer;
        if (worst != null) {
            int start = utf16(answer, worst.start());
            claim = answer.substring(start, Math.max(start, utf16(answer, worst.end())));
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("head", "groundedness");
        ev.put("unsupported", round(rs.unsupported()));
        ev.put("conflict", round(rs.conflict()));
        ev.put("premise_had_evidence", premiseHadEvidence);
        ev.put("flagged_sentences", sentences);
        ev.put("claim", claim.length() > CLAIM_ECHO_CHARS ? claim.substring(0, CLAIM_ECHO_CHARS) : claim);
        return mapper.valueToTree(ev).toString();
    }

    /** The UTF-16 index of the {@code codePoints}-th code point of {@code s}, clamped to the string. */
    static int utf16(String s, int codePoints) {
        int cp = Math.max(0, Math.min(codePoints, s.codePointCount(0, s.length())));
        return s.offsetByCodePoints(0, cp);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
