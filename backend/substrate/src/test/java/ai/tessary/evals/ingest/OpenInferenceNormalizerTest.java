// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.ingest.export.TraceSpanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Proves the normalizer round-trip: an OpenInference ({@code llm.*}) span and a native
 * {@code gen_ai.*} span carrying the same content normalize to the <em>same</em> canonical {@link RawEntry},
 * which re-emits via {@link TraceSpanMapper} to the same {@code gen_ai.*} span.
 */
class OpenInferenceNormalizerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode attrs(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    void detection_isDormantWithoutOpenInferenceKeys() throws Exception {
        assertFalse(OpenInferenceNormalizer.isOpenInference(attrs("{\"foo\":\"bar\",\"model\":\"gpt-4o\"}")));
        assertTrue(OpenInferenceNormalizer.isOpenInference(attrs("{\"llm.model_name\":\"gpt-4o\"}")));
        assertNull(OpenInferenceNormalizer.normalize(attrs("{\"foo\":1}")));
    }

    @Test
    void llmSpan_normalizesToCanonicalFields() throws Exception {
        JsonNode oi = attrs("""
                {
                  "openinference.span.kind": "LLM",
                  "llm.model_name": "claude-sonnet-4-6",
                  "llm.system": "anthropic",
                  "llm.input_messages": [
                    {"message.role":"system","message.content":"You are a planner"},
                    {"message.role":"user","message.content":"plan X"}
                  ],
                  "llm.output_messages": [{"message.role":"assistant","message.content":"the plan"}],
                  "llm.token_count.prompt": 11,
                  "llm.token_count.completion": 3,
                  "session.id": "sess-9"
                }
                """);

        OpenInferenceNormalizer.Canonical c = OpenInferenceNormalizer.normalize(oi);
        assertNotNull(c);
        assertEquals(KindNormalizer.LLM, c.operationKind());
        assertEquals("claude-sonnet-4-6", c.model());
        assertEquals("anthropic", c.system());
        assertEquals(11L, c.usage().get(GenAiAttributes.USAGE_INPUT_TOKENS));
        assertEquals(3L, c.usage().get(GenAiAttributes.USAGE_OUTPUT_TOKENS));

        JsonNode in = M.readTree(c.input());
        assertEquals(2, in.size());
        assertEquals("system", in.get(0).get("role").asText());
        assertEquals("You are a planner", in.get(0).get("content").asText());
        assertEquals("user", in.get(1).get("role").asText());
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
        // No gen_ai analogue → unknown kind.
        assertNull(GenAiAttributes.operationNameForSpanKind("GUARDRAIL"));
        assertNull(GenAiAttributes.operationNameForSpanKind(null));
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
    void outputToolCall_isKeptAsText() throws Exception {
        JsonNode oi = attrs("""
                {
                  "openinference.span.kind": "LLM",
                  "llm.model_name": "gpt-4o",
                  "llm.output_messages": [
                    {"message.role":"assistant","message.content":"",
                     "message.tool_calls":[{"tool_call.function.name":"get_weather",
                        "tool_call.function.arguments":"{\\"city\\":\\"NYC\\"}"}]}
                  ]
                }
                """);
        OpenInferenceNormalizer.Canonical c = OpenInferenceNormalizer.normalize(oi);
        assertNotNull(c);
        JsonNode out = M.readTree(c.output());
        String content = out.get(0).get("content").asText();
        assertTrue(content.contains("get_weather"), "tool call name preserved");
        assertTrue(content.contains("NYC"), "tool call arguments preserved (never truncated)");
    }

    @Test
    void messagesAsJsonEncodedStrings_areAccepted() throws Exception {
        // OTLP often delivers the message arrays as JSON-encoded string attribute values.
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
     * The acceptance round-trip: a native gen_ai.* RawEntry and the OpenInference-normalized RawEntry for the
     * same content emit the SAME gen_ai.* span (same system, operation.name, model, message structure, usage).
     */
    @Test
    void openInferenceAndNative_emitSameCanonicalSpan() throws Exception {
        // Multi-message input so the parity assertion below exercises every message position, not just index 0.
        String inputMessages =
                "[{\"role\":\"system\",\"content\":\"be terse\"},{\"role\":\"user\",\"content\":\"plan X\"}]";
        String outputText = "the plan";

        // Native gen_ai.* RawEntry (the shape Langfuse/upload already produce).
        RawEntry native_ = new RawEntry(
                "span-1",
                "https://src/1",
                "chat",
                inputMessages,
                outputText,
                "gpt-4o",
                java.util.Map.of(GenAiAttributes.USAGE_INPUT_TOKENS, 11L, GenAiAttributes.USAGE_OUTPUT_TOKENS, 3L),
                "parent-1",
                "trace-1",
                "2026-05-22T10:00:00Z",
                KindNormalizer.LLM);

        // OpenInference span carrying the same content.
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
                oi, "span-1", "https://src/1", "chat", "parent-1", "trace-1", "2026-05-22T10:00:00Z", null);
        assertNotNull(fromOi);

        // Same canonical RawEntry fields.
        assertEquals(native_.operationKind(), fromOi.operationKind());
        assertEquals(native_.model(), fromOi.model());

        ObjectNode nativeSpan = TraceSpanMapper.toSpan(native_, "svc");
        ObjectNode oiSpan = TraceSpanMapper.toSpan(fromOi, "svc");

        JsonNode na = nativeSpan.get("attributes");
        JsonNode oa = oiSpan.get("attributes");
        assertEquals(na.get(GenAiAttributes.SYSTEM), oa.get(GenAiAttributes.SYSTEM));
        assertEquals(na.get(GenAiAttributes.OPERATION_NAME), oa.get(GenAiAttributes.OPERATION_NAME));
        assertEquals("chat", oa.get(GenAiAttributes.OPERATION_NAME).asText());
        assertEquals(na.get(GenAiAttributes.REQUEST_MODEL), oa.get(GenAiAttributes.REQUEST_MODEL));
        assertEquals(11L, oa.get(GenAiAttributes.USAGE_INPUT_TOKENS).asLong());
        assertEquals(3L, oa.get(GenAiAttributes.USAGE_OUTPUT_TOKENS).asLong());

        // Same input/output message structure — assert the FULL arrays are structurally equal (every position),
        // not just index 0, so a regression in a later message would be caught.
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
     * A declared OpenInference provider/system must survive to the canonical {@code gen_ai.system} even when the
     * model name is NOT recognizable by {@code inferSystem} — otherwise the declared provider would silently
     * collapse to {@code "other"}. Uses a deliberately unknown model so {@code inferSystem} cannot recover it.
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
        // inferSystem alone would yield "other" for this model name.
        assertEquals("other", TraceSpanMapper.inferSystem("acme-frontier-7"));

        RawEntry e = OpenInferenceNormalizer.toRawEntry(oi, "s", "u", "chat", null, "t", null, null);
        assertNotNull(e);
        java.util.Map<String, Object> md = e.metadata();
        assertNotNull(md);
        assertEquals("anthropic", md.get(GenAiAttributes.SYSTEM));
        assertEquals("anthropic", md.get(GenAiAttributes.PROVIDER_NAME));

        ObjectNode span = TraceSpanMapper.toSpan(e, "svc");
        assertEquals(
                "anthropic",
                span.get("attributes").get(GenAiAttributes.SYSTEM).asText(),
                "declared provider preserved");
    }

    /**
     * The OpenInference tool-call name + id must land STRUCTURALLY (`gen_ai.tool.name` / `gen_ai.tool.call.id`),
     * so `TraceSpanMapper.emitUsageAndTool` re-emits them as span attributes — not only folded into output text.
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
        RawEntry e = OpenInferenceNormalizer.toRawEntry(oi, "s", "u", "chat", null, "t", null, null);
        assertNotNull(e);
        java.util.Map<String, Object> md = e.metadata();
        assertNotNull(md);
        assertEquals("get_weather", md.get(GenAiAttributes.TOOL_NAME));
        assertEquals("call_42", md.get(GenAiAttributes.TOOL_CALL_ID));

        ObjectNode span = TraceSpanMapper.toSpan(e, "svc");
        JsonNode a = span.get("attributes");
        assertEquals("get_weather", a.get(GenAiAttributes.TOOL_NAME).asText());
        assertEquals("call_42", a.get(GenAiAttributes.TOOL_CALL_ID).asText());
        // Full arguments are still preserved in the output-message text (never truncated).
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
        RawEntry e = OpenInferenceNormalizer.toRawEntry(oi, "s", "u", "agent", null, "t", null, null);
        assertNotNull(e);
        assertEquals(KindNormalizer.AGENT, e.operationKind());
        ObjectNode span = TraceSpanMapper.toSpan(e, "svc");
        assertEquals(
                GenAiAttributes.OP_INVOKE_AGENT,
                span.get("attributes").get(GenAiAttributes.OPERATION_NAME).asText());
    }
}
