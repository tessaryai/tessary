// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import org.jspecify.annotations.Nullable;

/** One chat wire: the request body, the credential header, and the parse of a 2xx answer. */
interface ChatCodec {

    ObjectNode body(ChatTarget target, ChatRequest request);

    void authorize(HttpRequest.Builder builder, String apiKey);

    /** @param response a JSON object, already checked */
    ChatResult parse(ChatTarget target, JsonNode response, long latencyMs);

    /** An integral count, else null; a present 0 stays 0. */
    static @Nullable Integer intOrNull(JsonNode node) {
        return node.canConvertToInt() && node.isIntegralNumber() ? node.intValue() : null;
    }

    static @Nullable String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    /** The id the provider echoed, else the one requested. */
    static String respondedModel(ChatTarget target, JsonNode response) {
        String echoed = textOrNull(response.path("model"));
        return echoed == null || echoed.isBlank() ? target.modelId() : echoed;
    }
}
