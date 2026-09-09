// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How we get a strictly-shaped JSON answer out of a model, for models that disagree about how to ask.
 *
 * <p><b>Why this exists.</b> Bedrock's Converse API has a native structured-output field
 * ({@code outputConfig}) that Anthropic models accept and Amazon Nova rejects outright with a 400 —
 * not a degradation, a refusal before the prompt is read. But every model that supports tool use can
 * be handed a single tool whose <i>arguments schema</i> is the shape we want, and told to call it;
 * the tool arguments come back as exactly the JSON the native field would have produced. So the same
 * contract has two wire encodings, and which one to use is a property of the model, not the caller.
 *
 * <p><b>The reusable bit.</b> A caller declares its shape ONCE as a {@link Spec} and never thinks
 * about the provider again: {@link ChatModelFactory.Resolved#structuredMode()} picks the encoding,
 * {@code LlmCaller.callStructured} applies it, and {@link #extract} unwraps either response shape to
 * the same JSON string. Adding a caller is a {@code Spec} constant plus one call; adding a model is
 * one {@link BedrockModelProfile} entry.
 *
 * <p>Deliberately a pure static utility with no Spring or OpenTelemetry dependency, so the encoding
 * and extraction rules are testable without a context. The tool-miss counter lives in
 * {@code LlmCaller}, which already holds the meter.
 */
public final class StructuredOutput {

    private StructuredOutput() {}

    /** Which wire encoding a model needs to produce a strictly-shaped answer. */
    public enum Mode {
        /** The provider's own structured-output field: Bedrock {@code outputConfig}, OpenAI {@code json_schema}. */
        NATIVE,
        /** A single tool whose arguments schema is the answer shape, with the model told to call it. */
        TOOL_CALL
    }

    /** Where {@link #extract} actually found the JSON, so a tool-mode miss is countable rather than silent. */
    public enum Source {
        /** The model called the tool, as instructed. */
        TOOL_CALL,
        /** The model answered in prose. Expected in {@link Mode#NATIVE}; a miss in {@link Mode#TOOL_CALL}. */
        TEXT,
        /** Neither a tool call nor any text. */
        NONE
    }

    /** The extracted JSON and where it came from. {@code json} is null exactly when source is {@link Source#NONE}. */
    public record Payload(@Nullable String json, Source source) {}

    /**
     * A structured-output contract: a named JSON schema and the bean it deserializes to.
     *
     * <p>Both encodings are derived from the SAME {@code schema}, which is the point — a caller
     * cannot accidentally let the two drift, and switching a lane to a model with different
     * capabilities changes no caller code.
     *
     * @param name identifies the schema natively and names the tool in tool mode, so it must be a
     *     valid tool name (Bedrock: alphanumeric, underscore, hyphen)
     * @param description ignored natively, but in tool mode it is the model's only cue for what the
     *     tool is for, so it must read as an instruction ("Submit your verdict...")
     */
    public record Spec<T>(String name, String description, JsonObjectSchema schema, Class<T> type) {

        public Spec {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("structured output name is required");
            if (description == null || description.isBlank()) {
                // Not cosmetic: in tool mode this is what tells the model to call the tool at all.
                throw new IllegalArgumentException("structured output description is required: " + name);
            }
            if (schema == null) throw new IllegalArgumentException("structured output schema is required: " + name);
            if (type == null) throw new IllegalArgumentException("structured output type is required: " + name);
        }

        /** The {@link Mode#NATIVE} encoding — what the provider's own structured-output field takes. */
        public ResponseFormat responseFormat() {
            return ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(
                            JsonSchema.builder().name(name).rootElement(schema).build())
                    .build();
        }

        /** The {@link Mode#TOOL_CALL} encoding — one tool whose arguments ARE the answer. */
        public ToolSpecification toolSpecification() {
            return ToolSpecification.builder()
                    .name(name)
                    .description(description)
                    .parameters(schema)
                    .build();
        }
    }

    /** Thrown when a response carries no parseable payload; callers catch it to apply their own fail-soft value. */
    public static class StructuredOutputException extends RuntimeException {
        public StructuredOutputException(String message) {
            super(message);
        }

        public StructuredOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Pull the JSON out of a response, whichever way the model chose to answer.
     *
     * <p>In {@link Mode#TOOL_CALL} the tool arguments are preferred, but we <b>fall back to the
     * message text</b>: a model told to call a tool may answer in prose anyway, and it very often
     * emits the right JSON in that prose, so failing outright would discard a usable answer. The
     * returned {@link Source} records which happened so the caller can count the miss.
     */
    public static Payload extract(ChatResponse response, Mode mode) {
        AiMessage message = response == null ? null : response.aiMessage();
        if (message == null) return new Payload(null, Source.NONE);

        if (mode == Mode.TOOL_CALL) {
            List<ToolExecutionRequest> calls = message.toolExecutionRequests();
            if (calls != null && !calls.isEmpty()) {
                String arguments = calls.getFirst().arguments();
                if (arguments != null && !arguments.isBlank()) {
                    return new Payload(arguments, Source.TOOL_CALL);
                }
            }
        }

        String text = message.text();
        if (text != null && !text.isBlank()) return new Payload(text, Source.TEXT);
        return new Payload(null, Source.NONE);
    }

    /**
     * Deserialize an extracted {@link Payload} into the spec's bean.
     *
     * @throws StructuredOutputException when there was no payload or it does not fit the schema —
     *     the single failure type callers catch, replacing per-caller {@code catch (Exception)} on
     *     {@code readValue}
     */
    public static <T> T parse(ObjectMapper mapper, Payload payload, Spec<T> spec) {
        if (payload.json() == null) {
            throw new StructuredOutputException("no structured output in response for " + spec.name());
        }
        try {
            return mapper.readValue(payload.json(), spec.type());
        } catch (Exception e) {
            throw new StructuredOutputException(
                    "structured output for " + spec.name() + " did not parse: " + e.getMessage(), e);
        }
    }
}
