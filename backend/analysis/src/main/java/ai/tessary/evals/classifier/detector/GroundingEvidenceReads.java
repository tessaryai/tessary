// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import java.util.Map;
import java.util.Set;

/**
 * Read seam for the Groundedness built-in: the SOURCE MATERIAL an observation's output is supposed to
 * be grounded in, when that material is not in the prompt.
 *
 * <p>The detector's original premise was the prompt alone ({@code system} + {@code user} text), which
 * is right for the document-in-prompt case and wrong for every retrieval- or tool-backed one. On a
 * {@code rag_answer} call site the evidence is the retrieved documents; on an agent turn it is the
 * tool results. Neither is in the prompt, so the entailment head was scoring the answer against the
 * QUESTION — which no answer entails — and firing on 89% of real traffic at one measured call site.
 * That is not a miscalibrated threshold; it is a premise that cannot contain the evidence.
 *
 * <p>An observation with no evidence rows is absent from the result rather than mapped to an empty
 * string, so the caller can tell "this turn produced no evidence" from "this turn's evidence is
 * blank" — the two require opposite responses (§ the detector's abstain rule).
 */
public interface GroundingEvidenceReads {

    /**
     * {@code span_id → concatenated evidence text} for the spans that have any.
     *
     * <p>Evidence is the {@code retrieved_doc} rows of the observation's CONVERSATION — the nearest
     * prior trace in the same conversation that retrieved anything, own trace first. A retrieval runs
     * on a sibling span (never the observation itself), and a multi-turn agent commonly retrieves once
     * and answers several follow-ups from that context without re-retrieving each turn, so trace-only
     * scope left every such follow-up with nothing. {@code tool_call} results are NOT evidence: the
     * entailment head cannot read a JSON result object, and treating one as a premise fired on 84 of 84
     * measured tool-backed answers. Their absence is what routes a tool-only turn into the abstain path
     * below.
     *
     * <p>Subjects arrive as {@link SpanRef} pairs rather than bare ids: v2 span identity is
     * {@code (project_id, trace_id, id)} and a span id alone does not address a row. The result is keyed
     * by the span id, which is the handle the detector already holds per scored subject; a detector
     * batch never mixes two spans that share an id across different traces.
     */
    Map<String, Evidence> groundingEvidence(String projectId, Set<SpanRef> spans);

    /** A span's producer identity within a project: the trace it belongs to, and its own id. */
    record SpanRef(String traceId, String spanId) {}

    /**
     * @param text the concatenated source material, empty when none was captured.
     * @param conversationDidExternalWork whether the conversation (up to and including this turn)
     *     contains any tool/mcp/retrieval/reranker span. This is what separates BLIND from GROUNDLESS: a
     *     turn whose conversation retrieved nothing and called nothing has no source by design, so the
     *     prompt is the whole world and an unsupported answer is a real finding. A turn that DID reach
     *     outside but whose results are not readable as a premise — never captured, or captured as a
     *     tool result object — is one we cannot judge, and scoring it would report our own blind spot as
     *     the agent's fault.
     */
    record Evidence(String text, boolean conversationDidExternalWork) {}
}
