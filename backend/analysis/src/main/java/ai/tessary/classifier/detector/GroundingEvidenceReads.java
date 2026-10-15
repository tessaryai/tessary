// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The source material an output should be grounded in when it is not in the prompt. Scoring against the prompt alone
 * fired on 89% of real traffic at one RAG call site. An observation with no evidence is absent from the result rather
 * than mapped to "", because the two require opposite responses.
 */
public interface GroundingEvidenceReads {

    /**
     * Evidence is the {@code retrieved_doc} rows of the nearest prior trace in the same conversation that retrieved
     * anything, own trace first. {@code tool_call} results are NOT evidence: the head cannot read JSON results (84 of
     * 84 measured tool-backed answers fired). Subjects are {@link SpanRef} pairs because a span id alone does not
     * address a row.
     */
    Map<String, Evidence> groundingEvidence(String projectId, Set<SpanRef> spans);

    record SpanRef(String traceId, String spanId) {}

    /**
     * @param documents one entry per {@code retrieved_doc} row, best first, never joined: the model was trained on
     * numbered passages and uses the boundaries @param conversationDidExternalWork separates BLIND from GROUNDLESS: a
     * conversation that reached out but captured nothing readable cannot be judged
     */
    record Evidence(List<String> documents, boolean conversationDidExternalWork) {
        public Evidence {
            documents = List.copyOf(documents);
        }
    }
}
