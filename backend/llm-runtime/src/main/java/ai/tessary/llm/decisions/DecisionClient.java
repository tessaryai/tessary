// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

/**
 * Asks a hosted decision model typed questions. Not a chat completion, but still booked: {@code JevDecisionClient}
 * gives it a GenAI span, book pricing and an {@code llm_call} row.
 */
public interface DecisionClient {

    /**
     * @param lane the {@code llm_call} lane the call is booked under @throws ai.tessary.open.errors.TessaryException
     * with a {@link ai.tessary.open.errors.DecisionError} code
     */
    DecisionAnswer decide(String projectId, String lane, DecisionTarget target, DecisionRequest request);
}
