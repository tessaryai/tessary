// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

/** The Anthropic Messages wire: the system prompt beside the turns, a required {@code max_tokens}, its own key header. */
final class AnthropicChatCodec implements ChatCodec {

    static final String API_VERSION = "2023-06-01";

    private final ObjectMapper mapper;
    private final int maxOutputTokens;

    AnthropicChatCodec(ObjectMapper mapper, int maxOutputTokens) {
        this.mapper = mapper;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public ObjectNode body(ChatTarget target, ChatRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", target.modelId());
        Integer maxTokens = request.maxTokens();
        body.put("max_tokens", maxTokens == null ? maxOutputTokens : maxTokens);
        List<String> system = new ArrayList<>();
        ArrayNode messages = mapper.createArrayNode();
        for (ChatMessage m : request.messages()) {
            if ("system".equals(m.role())) {
                system.add(m.content());
            } else {
                messages.addObject().put("role", m.role()).put("content", m.content());
            }
        }
        if (!system.isEmpty()) body.put("system", String.join("\n\n", system));
        body.set("messages", messages);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.topP() != null) body.put("top_p", request.topP());
        if (!request.stop().isEmpty()) body.set("stop_sequences", mapper.valueToTree(request.stop()));
        return body;
    }

    @Override
    public void authorize(HttpRequest.Builder builder, String apiKey) {
        builder.header("x-api-key", apiKey).header("anthropic-version", API_VERSION);
    }

    @Override
    public ChatResult parse(ChatTarget target, JsonNode response, long latencyMs) {
        JsonNode content = response.path("content");
        if (!content.isArray()) {
            throw new TessaryException(PlaygroundError.MALFORMED_RESPONSE, target.provider(), "no content");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        JsonNode usage = response.path("usage");
        return new ChatResult(
                text.toString(),
                ChatCodec.textOrNull(response.path("stop_reason")),
                ChatCodec.respondedModel(target, response),
                new ChatUsage(
                        ChatCodec.intOrNull(usage.path("input_tokens")),
                        ChatCodec.intOrNull(usage.path("output_tokens")),
                        ChatCodec.intOrNull(usage.path("cache_read_input_tokens")),
                        ChatCodec.intOrNull(usage.path("cache_creation_input_tokens")),
                        null),
                latencyMs);
    }
}
