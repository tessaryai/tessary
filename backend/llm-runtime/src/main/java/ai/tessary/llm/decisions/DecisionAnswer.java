// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import ai.tessary.llm.ModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One decision call's outcome, with the exact bodies kept as the caller's audit record. @param respondedModel the model
 * the provider echoed, e.g. a dated version @param costUsd priced from the book under {@code typesafe/<bare id>}; null
 * when no book carries it @param priceBookVersion non-null exactly when {@code costUsd} is
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

    /** @param probabilities option key to probability for a choice question; empty otherwise */
    public record Answer(
            String type,
            @Nullable String choice,
            @Nullable Double score,
            @Nullable Double confidence,
            Map<String, Double> probabilities) {

        public Answer {
            probabilities = Map.copyOf(probabilities);
        }

        /** 0 when the provider did not list {@code option}. */
        public double probability(String option) {
            return probabilities.getOrDefault(option, 0.0);
        }
    }
}
