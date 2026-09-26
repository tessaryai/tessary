// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.export.TraceSpanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * An OpenInference ({@code llm.*}) span and a native {@code gen_ai.*} span with the same content normalize to the
 * same {@link RawEntry} and re-emit the same span via {@link TraceSpanMapper}.
 */
class OpenInferenceNormalizerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode attrs(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    void detection_isDormantWithoutOpenInferenceKeys() {
        assertFalse(OpenInferenceNormalizer.isOpenInference(Map.of("foo", "bar", "model", "gpt-4o")));
        assertTrue(OpenInferenceNormalizer.isOpenInference(Map.of("llm.model_name", "gpt-4o")));
    }

    @Test
    void spanKind_mapsToCanonicalOperation() {
        assertEquals(KindNormalizer.AGENT, KindNormalizer.normalize(GenAiAttributes.operationNameForSpanKind("AGENT")));
        assertEquals(KindNormalizer.TOOL, KindNormalizer.normalize(GenAiAttributes.operationNameForSpanKind("TOOL")));
        assertEquals(
                KindNormalizer.RETRIEVAL,
                KindNormalizer.normalize(GenAiAttributes.operationNameForSpanKind("RETRIEVER")));
        assertEquals(
                KindNormalizer.WORKFLOW, KindNormalizer.normalize(GenAiAttributes.operationNameForSpanKind("CHAIN")));
        // No gen_ai analogue: unknown kind.
        assertNull(GenAiAttributes.operationNameForSpanKind("GUARDRAIL"));
        assertNull(GenAiAttributes.operationNameForSpanKind(null));
    }

    @Test
    void embeddingSpanKind_isItsOwnKindNotARetrieval() {
        assertEquals(
                KindNormalizer.EMBEDDING,
                KindNormalizer.normalize(GenAiAttributes.operationNameForSpanKind("embedding")));
    }

    @Test
    void multimodalContents_areConcatenatedNotDropped() throws Exception {
        JsonNode oi = attrs("""
                {
                  "openinference.span.kind": "LLM",
                  "llm.model_name": "gpt-4o",
                  "llm.input_messages": [
                    {"message.role":"user","message.contents":[
                       {"message_content.type":"text","message_content.text":"hi"},
                       {"message_content.type":"text","message_content.text":"there"}
                    ]}
                  ]
                }
                """);
        OpenInferenceNormalizer.Canonical c = OpenInferenceNormalizer.normalize(oi);
        assertNotNull(c);
        JsonNode in = M.readTree(c.input());
        assertEquals("hi\nthere", in.get(0).get("content").asText());
    }

    @Test
    void messagesAsJsonEncodedStrings_areAccepted() throws Exception {
        // OTLP often delivers message arrays as JSON-encoded strings.
        JsonNode oi = attrs("""
                {
                  "openinference.span.kind": "LLM",
                  "llm.model_name": "gpt-4o",
                  "llm.input_messages": "[{\\"message.role\\":\\"user\\",\\"message.content\\":\\"hello\\"}]"
                }
                """);
        OpenInferenceNormalizer.Canonical c = OpenInferenceNormalizer.normalize(oi);
        assertNotNull(c);
        JsonNode in = M.readTree(c.input());
        assertEquals("hello", in.get(0).get("content").asText());
    }

    /**
     * Native and OpenInference entries for the same content emit the same gen_ai.* span: system, operation, model,
     * messages, and usage.
     */
    @Test
    void openInferenceAndNative_emitSameCanonicalSpan() throws Exception {
        // Several messages, so parity checks every position.
        String inputMessages =
                "[{\"role\":\"system\",\"content\":\"be terse\"},{\"role\":\"user\",\"content\":\"plan X\"}]";
        String outputText = "the plan";

        RawEntry native_ = new RawEntry(
                "span-1",
                "chat",
                inputMessages,
                outputText,
                "gpt-4o",
                java.util.Map.of(GenAiAttributes.USAGE_INPUT_TOKENS, 11L, GenAiAttributes.USAGE_OUTPUT_TOKENS, 3L),
                "parent-1",
                "trace-1",
                "2026-05-22T10:00:00Z",
                KindNormalizer.LLM);

        JsonNode oi = attrs("""
                {
                  "openinference.span.kind":"LLM",
                  "llm.model_name":"gpt-4o",
                  "llm.input_messages":[{"message.role":"system","message.content":"be terse"},{"message.role":"user","message.content":"plan X"}],
                  "llm.output_messages":[{"message.role":"assistant","message.content":"the plan"}],
                  "llm.token_count.prompt":11,
                  "llm.token_count.completion":3
                }
                """);
        RawEntry fromOi = OpenInferenceNormalizer.toRawEntry(
                oi, "span-1", "chat", "parent-1", "trace-1", "2026-05-22T10:00:00Z", null);
        assertNotNull(fromOi);

        assertEquals(native_.operationKind(), fromOi.operationKind());
        assertEquals(native_.model(), fromOi.model());

        ObjectNode nativeSpan = TraceSpanMapper.toSpan(native_, "svc", null, null);
        ObjectNode oiSpan = TraceSpanMapper.toSpan(fromOi, "svc", null, null);

        JsonNode na = nativeSpan.get("attributes");
        JsonNode oa = oiSpan.get("attributes");
        assertEquals(na.get(GenAiAttributes.SYSTEM), oa.get(GenAiAttributes.SYSTEM));
        assertEquals(na.get(GenAiAttributes.OPERATION_NAME), oa.get(GenAiAttributes.OPERATION_NAME));
        assertEquals("chat", oa.get(GenAiAttributes.OPERATION_NAME).asText());
        assertEquals(na.get(GenAiAttributes.REQUEST_MODEL), oa.get(GenAiAttributes.REQUEST_MODEL));
        assertEquals(11L, oa.get(GenAiAttributes.USAGE_INPUT_TOKENS).asLong());
        assertEquals(3L, oa.get(GenAiAttributes.USAGE_OUTPUT_TOKENS).asLong());

        // The full arrays, so a regression in a later message is caught.
        JsonNode inN = M.readTree(na.get(GenAiAttributes.INPUT_MESSAGES).asText());
        JsonNode inO = M.readTree(oa.get(GenAiAttributes.INPUT_MESSAGES).asText());
        assertEquals(2, inO.size(), "both input messages normalized");
        assertEquals(inN, inO, "full input message arrays match");
        JsonNode outN = M.readTree(na.get(GenAiAttributes.OUTPUT_MESSAGES).asText());
        JsonNode outO = M.readTree(oa.get(GenAiAttributes.OUTPUT_MESSAGES).asText());
        assertEquals(outN, outO, "full output message arrays match");
        assertEquals("the plan", outO.get(0).get("parts").get(0).get("content").asText());
    }

    /**
     * A declared provider survives to {@code gen_ai.system} even for a model {@code inferSystem} cannot recognize,
     * instead of collapsing to "other".
     */
    @Test
    void declaredSystem_survivesWhenModelNotInferable() throws Exception {
        JsonNode oi = attrs("""
                {
                  "openinference.span.kind":"LLM",
                  "llm.model_name":"acme-frontier-7",
                  "llm.system":"anthropic",
                  "llm.input_messages":[{"message.role":"user","message.content":"hi"}]
                }
                """);
        assertEquals("other", TraceSpanMapper.inferSystem("acme-frontier-7"));

        RawEntry e = OpenInferenceNormalizer.toRawEntry(oi, "s", "chat", null, "t", null, null);
        assertNotNull(e);
        java.util.Map<String, Object> md = e.metadata();
        assertNotNull(md);
        assertEquals("anthropic", md.get(GenAiAttributes.SYSTEM));
        assertEquals("anthropic", md.get(GenAiAttributes.PROVIDER_NAME));

        ObjectNode span = TraceSpanMapper.toSpan(e, "svc", null, null);
        assertEquals(
                "anthropic",
                span.get("attributes").get(GenAiAttributes.SYSTEM).asText(),
                "declared provider preserved");
    }

    /**
     * The tool name and id land structurally, so {@code TraceSpanMapper.emitUsageAndTool} re-emits them as
     * attributes.
     */
    @Test
    void toolCall_landsAsStructuredGenAiToolAttributes() throws Exception {
        JsonNode oi = attrs("""
                {
                  "openinference.span.kind":"LLM",
                  "llm.model_name":"gpt-4o",
                  "llm.output_messages":[
                    {"message.role":"assistant","message.content":"",
                     "message.tool_calls":[{"tool_call.id":"call_42",
                        "tool_call.function.name":"get_weather",
                        "tool_call.function.arguments":"{\\"city\\":\\"NYC\\"}"}]}
                  ]
                }
                """);
        RawEntry e = OpenInferenceNormalizer.toRawEntry(oi, "s", "chat", null, "t", null, null);
        assertNotNull(e);
        java.util.Map<String, Object> md = e.metadata();
        assertNotNull(md);
        assertEquals("get_weather", md.get(GenAiAttributes.TOOL_NAME));
        assertEquals("call_42", md.get(GenAiAttributes.TOOL_CALL_ID));

        ObjectNode span = TraceSpanMapper.toSpan(e, "svc", null, null);
        JsonNode a = span.get("attributes");
        assertEquals("get_weather", a.get(GenAiAttributes.TOOL_NAME).asText());
        assertEquals("call_42", a.get(GenAiAttributes.TOOL_CALL_ID).asText());
        // Full arguments stay in the output text, never truncated.
        JsonNode out = M.readTree(a.get(GenAiAttributes.OUTPUT_MESSAGES).asText());
        assertTrue(
                out.get(0).get("parts").get(0).get("content").asText().contains("NYC"),
                "tool arguments preserved in output text");
    }

    @Test
    void agentSpanKind_reEmitsInvokeAgentOperationName() throws Exception {
        JsonNode oi = attrs("""
                {
                  "openinference.span.kind":"AGENT",
                  "llm.model_name":"claude-sonnet-4-6",
                  "llm.input_messages":[{"message.role":"user","message.content":"do the task"}]
                }
                """);
        RawEntry e = OpenInferenceNormalizer.toRawEntry(oi, "s", "agent", null, "t", null, null);
        assertNotNull(e);
        assertEquals(KindNormalizer.AGENT, e.operationKind());
        ObjectNode span = TraceSpanMapper.toSpan(e, "svc", null, null);
        assertEquals(
                GenAiAttributes.OP_INVOKE_AGENT,
                span.get("attributes").get(GenAiAttributes.OPERATION_NAME).asText());
    }

    /**
     * The first self-naming tool call is carried structurally, even past unnamed ones; non-array or non-JSON output
     * carries none rather than failing the span.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "null",
            value = {
                "[{\"message.tool_calls\":[{}]},{\"message.tool_calls\":[{\"tool_call.function.name\":\"lookup\"}]}] | lookup",
                "[{\"message.tool_calls\":[{}]}] | null",
                "\"not json\" | null",
                "5 | null"
            })
    void firstNamedToolCall_isCarriedStructurally(String outputMessages, @Nullable String toolName) throws Exception {
        var canonical = OpenInferenceNormalizer.normalize(
                attrs("{\"openinference.span.kind\":\"LLM\",\"llm.output_messages\":" + outputMessages + "}"));

        assertEquals(toolName, canonical.usage().get(GenAiAttributes.TOOL_NAME));
    }
}
