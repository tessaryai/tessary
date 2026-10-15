// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.CallSiteShapeReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.open.hash.Sha256;
import ai.tessary.pipeline.CallSiteFact;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The contract is "unsupported" (P(BASELESS) + P(CONFLICT)), not "contradicts": contradiction alone measured
 * unreachable at this model size, while unsupported is what annotators labelled (audit:
 * {@code classifiers/groundedness/README.md}). Retrieved documents reach the head as numbered passages, the trained
 * layout; with no evidence the prompt is the premise. Tool results are not evidence, so a tool-backed
 * {@code rag_answer} abstains. Every scored answer gets an assessment row, since the rate test needs passes as well as
 * flags.
 */
public final class GroundednessDetector implements BuiltInDetector {

    /** A detection is a pointer, not a payload. */
    private static final int CLAIM_ECHO_CHARS = 300;

    static final String MODEL = "tessaryai/groundedness-classifier-v1@6746fa25f4f6cdb60f994f056c1919300e6c2b12";

    /** Part of {@link #scorerVersion}: a layout change changes what a score means. */
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

    /** A shape arriving invalidates everything already swept; see {@link BuiltInDetector#callSiteFactsRead}. */
    @Override
    public Set<CallSiteFact> callSiteFactsRead() {
        return Set.of(CallSiteFact.SHAPE);
    }

    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        throw new UnsupportedOperationException("groundedness scores only through sweepBatch");
    }

    /** An answer the inputs skip or the encoder refused writes nothing: it is not a trial. */
    @Override
    public List<Detection> sweepBatch(ClassifierRow signal, List<SubstrateObservation> batch, @Nullable String config) {
        return score(signal, batch, config);
    }

    /**
     * A change to the model, layout or threshold starts a new set of assessment rows, since a flag under each is a
     * different event.
     */
    public static String scorerVersion(double threshold) {
        String material = MODEL + "|" + ENCODING + "|" + String.format(Locale.ROOT, "%.4f", threshold);
        return "gnd-v1-" + HexFormat.of().formatHex(Sha256.digest(material), 0, 6);
    }

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
     * Offsets are UTF-16 code units, converted from the model's code points. {@code claim} is kept for readers of the
     * older shape.
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

    static int utf16(String s, int codePoints) {
        int cp = Math.max(0, Math.min(codePoints, s.codePointCount(0, s.length())));
        return s.offsetByCodePoints(0, cp);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
