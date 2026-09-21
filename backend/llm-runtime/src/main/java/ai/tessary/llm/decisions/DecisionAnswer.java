// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import ai.tessary.llm.ModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One decision call's outcome: the parsed answers, what the call cost, and the exact bodies sent and
 * received, which a caller keeps as its audit record.
 *
 * @param provider the gateway that carried the call
 * @param requestedModel the model id sent, e.g. {@code jev-latest}
 * @param respondedModel the model the provider echoed, e.g. a dated Jev version
 * @param answers question name to answer, one per question asked
 * @param costUsd priced from the book under {@code typesafe/<bare id>}; null when no book carries it
 * @param priceBookVersion the book that priced it, non-null exactly when {@code costUsd} is
 * @param requestBody the exact JSON body sent
 * @param responseBody the exact JSON body received, including any provider-reported cost
 */
public record DecisionAnswer(
        ModelProvider provider,
        String requestedModel,
        String respondedModel,
        Map<String, Answer> answers,
        @Nullable Integer inputTokens,
        @Nullable Integer outputTokens,
        @Nullable BigDecimal costUsd,
        @Nullable String priceBookVersion,
        long latencyMs,
        JsonNode requestBody,
        JsonNode responseBody) {

    public DecisionAnswer {
        answers = Map.copyOf(answers);
    }

    /**
     * One question's answer.
     *
     * @param choice the picked option, for a choice question
     * @param score the score, for a score question
     * @param probabilities option key to probability, for a choice question; empty otherwise
     */
    public record Answer(
            String type,
            @Nullable String choice,
            @Nullable Double score,
            @Nullable Double confidence,
            Map<String, Double> probabilities) {

        public Answer {
            probabilities = Map.copyOf(probabilities);
        }

        /** The probability of {@code option}, or 0 when the provider did not list it. */
        public double probability(String option) {
            return probabilities.getOrDefault(option, 0.0);
        }
    }
}
