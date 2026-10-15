// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A decision call's {@code state} (a string or JSON object) and named questions about it; the wire body adds only the
 * model id. @param questions sent in this order
 */
public record DecisionRequest(JsonNode state, Map<String, Question> questions) {

    public DecisionRequest {
        questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
    }

    /**
     * @param type {@code choice}, {@code score} or {@code noul} @param criteria for a choice, each option key to its
     * description, in order; empty otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Question(String type, String instructions, Map<String, String> criteria) {

        public Question {
            criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
        }

        /** The provider picks one of {@code criteria}'s keys and scores them all. */
        public static Question choice(String instructions, Map<String, String> criteria) {
            return new Question("choice", instructions, criteria);
        }
    }
}
