// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

/**
 * The extraction rules that let one declared shape survive two wire encodings. These are what make
 * the mechanism reusable, so they are pinned independently of any caller: a change here silently
 * changes every lane that adopts {@code callStructured}.
 */
class StructuredOutputTest {

    record Verdict(boolean passed, String rationale) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final StructuredOutput.Spec<Verdict> SPEC = new StructuredOutput.Spec<>(
            "submit_verdict",
            "Submit your verdict.",
            JsonObjectSchema.builder()
                    .addBooleanProperty("passed")
                    .addStringProperty("rationale")
                    .required("passed", "rationale")
                    .build(),
            Verdict.class);

    private static final String JSON = "{\"passed\":true,\"rationale\":\"looks right\"}";

    private static ChatResponse response(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }

    @Test
    void bothEncodingsComeFromTheSameSchema() {
        // The point of Spec: a caller declares the shape once and cannot let the two drift.
        assertEquals("submit_verdict", SPEC.responseFormat().jsonSchema().name());
        assertEquals("submit_verdict", SPEC.toolSpecification().name());
        assertEquals(
                SPEC.responseFormat().jsonSchema().rootElement(),
                SPEC.toolSpecification().parameters(),
                "the tool's arguments schema IS the response schema");
    }

    @Test
    void toolModeReadsTheToolArguments() {
        ChatResponse r = response(AiMessage.from(ToolExecutionRequest.builder()
                .name("submit_verdict")
                .arguments(JSON)
                .build()));

        StructuredOutput.Payload payload = StructuredOutput.extract(r, StructuredOutput.Mode.TOOL_CALL);
        assertEquals(StructuredOutput.Source.TOOL_CALL, payload.source());
        assertEquals(new Verdict(true, "looks right"), StructuredOutput.parse(MAPPER, payload, SPEC));
    }

    @Test
    void nativeModeReadsTheMessageText() {
        StructuredOutput.Payload payload =
                StructuredOutput.extract(response(new AiMessage(JSON)), StructuredOutput.Mode.NATIVE);
        assertEquals(StructuredOutput.Source.TEXT, payload.source());
        assertEquals(new Verdict(true, "looks right"), StructuredOutput.parse(MAPPER, payload, SPEC));
    }

    /**
     * A model told to call a tool may answer in prose anyway, and usually still emits the right JSON.
     * Discarding a usable answer over the delivery mechanism would be strictly worse, so the fallback
     * salvages it — and reports TEXT so the caller can count the miss rather than never learning of it.
     */
    @Test
    void toolModeFallsBackToTextWhenTheModelIgnoresTheTool() {
        StructuredOutput.Payload payload =
                StructuredOutput.extract(response(new AiMessage(JSON)), StructuredOutput.Mode.TOOL_CALL);
        assertEquals(StructuredOutput.Source.TEXT, payload.source(), "the miss must stay visible");
        assertEquals(new Verdict(true, "looks right"), StructuredOutput.parse(MAPPER, payload, SPEC));
    }

    @Test
    void anEmptyResponseIsAFailureNotAnEmptyBean() {
        StructuredOutput.Payload payload =
                StructuredOutput.extract(response(new AiMessage("")), StructuredOutput.Mode.TOOL_CALL);
        assertEquals(StructuredOutput.Source.NONE, payload.source());
        assertNull(payload.json());
        assertThrows(
                StructuredOutput.StructuredOutputException.class, () -> StructuredOutput.parse(MAPPER, payload, SPEC));
    }

    @Test
    void unparseableProseSurfacesAsTheOneFailureTypeCallersCatch() {
        StructuredOutput.Payload payload = StructuredOutput.extract(
                response(new AiMessage("I think it passed, honestly")), StructuredOutput.Mode.NATIVE);
        assertThrows(
                StructuredOutput.StructuredOutputException.class, () -> StructuredOutput.parse(MAPPER, payload, SPEC));
    }

    @Test
    void aSpecWithoutADescriptionIsRejectedAtConstruction() {
        // Under tool mode the description is the model's only cue to call the tool, so an empty one is
        // a silent reliability bug rather than a cosmetic omission.
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuredOutput.Spec<>(
                        "submit_verdict", "  ", JsonObjectSchema.builder().build(), Verdict.class));
    }
}
