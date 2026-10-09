// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.substrate.CallSiteShapeReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the groundedness model is sent for an answer: the passages, the question and the answer. The
 * detector reads its inputs here and so does anything that later shows a scored answer, so the
 * sentence offsets the model returned index into the same answer string a reader is shown.
 *
 * <p>The rules, in the order they apply. An answer is scored only on a call site whose shape carries
 * source content ({@link #GROUNDED_SHAPES}), only when it is not blank, and only when it asserts
 * something checkable ({@link VerifiableClaims}). Its passages are the retrieved documents, with the
 * user's text as the question, or, when the turn retrieved nothing, the prompt as one passage with no
 * question. A {@code rag_answer} turn whose conversation reached outside and captured nothing readable
 * is abstained: its source is not ours to judge. Anything this skips is not a trial.
 */
public final class GroundednessInputs {

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

    private static final GroundingEvidenceReads.Evidence NO_EVIDENCE =
            new GroundingEvidenceReads.Evidence(List.of(), false);

    /**
     * One answer's model inputs.
     *
     * @param question the user's text when the passages are retrieved documents; null when the prompt is
     *     the passage
     * @param premiseHadEvidence whether the passages are retrieved documents rather than the prompt
     */
    public record Inputs(List<String> passages, @Nullable String question, String answer, boolean premiseHadEvidence) {}

    private final CallSiteShapeReads shapes;
    private final GroundingEvidenceReads evidenceReads;

    public GroundednessInputs(CallSiteShapeReads shapes, GroundingEvidenceReads evidenceReads) {
        this.shapes = shapes;
        this.evidenceReads = evidenceReads;
    }

    /**
     * The inputs for each observation in {@code batch}, index-aligned: null for one that is not scored.
     * One shape read and one evidence read for the whole batch, which is one project's.
     */
    public List<@Nullable Inputs> read(List<SubstrateObservation> batch) {
        List<@Nullable Inputs> out = new ArrayList<>(batch.size());
        Set<String> siteIds = new HashSet<>();
        for (SubstrateObservation o : batch) {
            out.add(null);
            if (o.callSiteId() != null) siteIds.add(o.callSiteId());
        }
        if (siteIds.isEmpty()) return out;
        String projectId = batch.get(0).projectId();
        Map<String, String> shapeBySite = shapes.callSiteShapes(projectId, siteIds);
        if (shapeBySite.isEmpty()) return out;

        // Paired (trace, span) subjects: a v2 span id addresses a row only alongside its trace.
        Set<GroundingEvidenceReads.SpanRef> scorable = new HashSet<>();
        for (SubstrateObservation o : batch) {
            if (GROUNDED_SHAPES.contains(shapeOf(o, shapeBySite))) {
                scorable.add(new GroundingEvidenceReads.SpanRef(o.traceId(), o.observationId()));
            }
        }
        Map<String, GroundingEvidenceReads.Evidence> evidenceById =
                scorable.isEmpty() ? Map.of() : evidenceReads.groundingEvidence(projectId, scorable);

        for (int i = 0; i < batch.size(); i++) {
            SubstrateObservation o = batch.get(i);
            String siteShape = shapeOf(o, shapeBySite);
            if (!GROUNDED_SHAPES.contains(siteShape)) continue;
            out.set(i, inputs(o, siteShape, evidenceById.getOrDefault(o.observationId(), NO_EVIDENCE)));
        }
        return out;
    }

    private static @Nullable Inputs inputs(
            SubstrateObservation o, String siteShape, GroundingEvidenceReads.Evidence ev) {
        String answer = o.outputText();
        if (answer.isBlank()) return null;
        // An answer that asserts nothing checkable never reaches the head: measured on real turns with
        // no evidence, greetings and questions were 48 of 54 false firings. See VerifiableClaims. The
        // head scores the WHOLE answer; this is a gate, not a splitter.
        if (VerifiableClaims.of(answer).isEmpty()) return null;
        List<String> documents = ev.documents();
        // ABSTAIN only when BLIND on a shape that HAS no prompt fallback — the conversation reached
        // outside and captured nothing readable, so the answer's source is not ours to judge. Not
        // abstained: a conversation that reached outside for nothing (the prompt is the whole world),
        // and extract/summarize, whose document is in the prompt.
        if (documents.isEmpty() && ev.conversationDidExternalWork() && EVIDENCE_EXPECTED_SHAPES.contains(siteShape)) {
            return null;
        }
        if (!documents.isEmpty()) {
            String q = o.inputText();
            return new Inputs(documents, q.isBlank() ? null : q, answer, true);
        }
        String premise = o.groundingPremiseText();
        if (premise.isBlank()) return null;
        return new Inputs(List.of(premise), null, answer, false);
    }

    private static String shapeOf(SubstrateObservation o, Map<String, String> shapeBySite) {
        String shape = o.callSiteId() == null ? null : shapeBySite.get(o.callSiteId());
        return shape == null ? "" : shape;
    }
}
