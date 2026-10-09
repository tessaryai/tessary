// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one decision call asks: a {@code state} (a string or a JSON object) and named, typed questions
 * about it. The wire body is this plus the model id, exactly as the provider documents it.
 *
 * @param state what the questions are about, sent as is
 * @param questions question name to question, in the order they are sent
 */
public record DecisionRequest(JsonNode state, Map<String, Question> questions) {

    public DecisionRequest {
        questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
    }

    /**
     * One question in the provider's documented shape.
     *
     * @param type {@code choice}, {@code score} or {@code noul}
     * @param instructions the question text
     * @param criteria for a choice, each option key to its description, in order; empty otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Question(String type, String instructions, Map<String, String> criteria) {

        public Question {
            criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
        }

        /** A choice question: the provider picks one of {@code criteria}'s keys and scores them all. */
        public static Question choice(String instructions, Map<String, String> criteria) {
            return new Question("choice", instructions, criteria);
        }
    }
}
