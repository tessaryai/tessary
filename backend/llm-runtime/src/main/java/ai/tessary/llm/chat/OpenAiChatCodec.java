// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import ai.tessary.llm.ModelProvider;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import org.jspecify.annotations.Nullable;

/** The OpenAI chat completions wire, which every OpenAI-compatible provider (OpenRouter, Gemini, CUSTOM...) speaks. */
final class OpenAiChatCodec implements ChatCodec {

    private final ObjectMapper mapper;

    OpenAiChatCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ObjectNode body(ChatTarget target, ChatRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", target.modelId());
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            messages.addObject().put("role", m.role()).put("content", m.content());
        }
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) {
            // OpenAI renamed the cap; the compatible providers still read the old name.
            String key = target.provider() == ModelProvider.OPENAI ? "max_completion_tokens" : "max_tokens";
            body.put(key, request.maxTokens());
        }
        if (request.topP() != null) body.put("top_p", request.topP());
        if (!request.stop().isEmpty()) body.set("stop", mapper.valueToTree(request.stop()));
        // OpenRouter reports what it billed only when asked.
        if (target.billedByOpenRouter()) body.putObject("usage").put("include", true);
        return body;
    }

    @Override
    public void authorize(HttpRequest.Builder builder, String apiKey) {
        builder.header("Authorization", "Bearer " + apiKey);
    }

    @Override
    public ChatResult parse(ChatTarget target, JsonNode response, long latencyMs) {
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            String why = response.path("error").isObject()
                    ? HttpChatClient.refusal(mapper, response.toString())
                    : "no choices";
            throw new TessaryException(PlaygroundError.MALFORMED_RESPONSE, target.provider(), why);
        }
        JsonNode choice = choices.get(0);
        String content = ChatCodec.textOrNull(choice.path("message").path("content"));
        JsonNode usage = response.path("usage");
        Integer prompt = ChatCodec.intOrNull(usage.path("prompt_tokens"));
        Integer cached = ChatCodec.intOrNull(usage.path("prompt_tokens_details").path("cached_tokens"));
        // prompt_tokens includes the cached reads, which the ledger books in their own bucket.
        Integer input = prompt == null ? null : Math.max(0, prompt - (cached == null ? 0 : cached));
        return new ChatResult(
                content == null ? "" : content,
                ChatCodec.textOrNull(choice.path("finish_reason")),
                ChatCodec.respondedModel(target, response),
                new ChatUsage(
                        input,
                        ChatCodec.intOrNull(usage.path("completion_tokens")),
                        cached,
                        null,
                        reportedCost(usage.path("cost"))),
                latencyMs);
    }

    private static @Nullable BigDecimal reportedCost(JsonNode cost) {
        return cost.isNumber() ? cost.decimalValue() : null;
    }
}
