// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

/**
 * Asks a hosted decision model typed questions about a state. A decision call is a hosted-classifier
 * call, not a chat completion: it does not go through {@code ChatModel}, and {@code JevDecisionClient}
 * gives it the GenAI span, the book pricing and the {@code llm_call} row {@code LlmCaller} gives a chat.
 */
public interface DecisionClient {

    /**
     * Send one request and return its parsed answers.
     *
     * @param lane the {@code llm_call} lane the call is booked under
     * @throws ai.tessary.open.errors.TessaryException with a {@link ai.tessary.open.errors.DecisionError}
     *     code when the key is refused, the provider stays unavailable through every retry, the request
     *     is refused, or the answer does not match the questions
     */
    DecisionAnswer decide(String projectId, String lane, DecisionTarget target, DecisionRequest request);
}
