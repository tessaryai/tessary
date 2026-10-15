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
 * What the groundedness model is sent for an answer. Shared by the detector and every reader so returned sentence
 * offsets index into the same answer string. Only {@link #GROUNDED_SHAPES}, non-blank answers that assert something
 * checkable are scored; a {@code rag_answer} turn that reached outside and captured nothing readable is abstained.
 */
public final class GroundednessInputs {

    /** Open-generation shapes have no source to be ungrounded from. */
    static final Set<String> GROUNDED_SHAPES = Set.of("summarize", "extract", "rag_answer");

    /**
     * {@code extract} and {@code summarize} are absent: their document is in the prompt, so a tool span elsewhere in
     * the trace must not cost them their premise.
     */
    private static final Set<String> EVIDENCE_EXPECTED_SHAPES = Set.of("rag_answer");

    private static final GroundingEvidenceReads.Evidence NO_EVIDENCE =
            new GroundingEvidenceReads.Evidence(List.of(), false);

    /**
     * @param question null when the prompt is the passage @param premiseHadEvidence whether the passages are retrieved
     * documents rather than the prompt
     */
    public record Inputs(List<String> passages, @Nullable String question, String answer, boolean premiseHadEvidence) {}

    private final CallSiteShapeReads shapes;
    private final GroundingEvidenceReads evidenceReads;

    public GroundednessInputs(CallSiteShapeReads shapes, GroundingEvidenceReads evidenceReads) {
        this.shapes = shapes;
        this.evidenceReads = evidenceReads;
    }

    /** Index-aligned, null when not scored. One shape read and one evidence read per batch. */
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
        // Greetings and questions were 48 of 54 false firings on evidence-less turns. A gate, not a splitter: the head
        // scores the whole answer.
        if (VerifiableClaims.of(answer).isEmpty()) return null;
        List<String> documents = ev.documents();
        // Abstain only when BLIND on a shape with no prompt fallback.
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
