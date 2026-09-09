// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one place the OTel {@code gen_ai.operation.name} standard maps to the platform's canonical operation
 * kind. Open/Development enum, so unknown or absent values must normalize to {@code null}, not throw.
 */
class KindNormalizerTest {

    @Test
    void mapsOtelOperationNamesToKinds() {
        assertEquals(KindNormalizer.LLM, KindNormalizer.normalize("chat"));
        assertEquals(KindNormalizer.LLM, KindNormalizer.normalize("text_completion"));
        assertEquals(KindNormalizer.LLM, KindNormalizer.normalize("generate_content"));
        assertEquals(KindNormalizer.AGENT, KindNormalizer.normalize("invoke_agent"));
        assertEquals(KindNormalizer.AGENT, KindNormalizer.normalize("create_agent"));
        assertEquals(KindNormalizer.WORKFLOW, KindNormalizer.normalize("invoke_workflow"));
        assertEquals(KindNormalizer.TOOL, KindNormalizer.normalize("execute_tool"));
        // embeddings is a distinct kind from retrieval (the viewer segregates the two).
        assertEquals(KindNormalizer.EMBEDDING, KindNormalizer.normalize("embeddings"));
        assertEquals(KindNormalizer.RETRIEVAL, KindNormalizer.normalize("retrieval"));
        assertEquals(KindNormalizer.RERANKER, KindNormalizer.normalize("rerank"));
        assertEquals(KindNormalizer.GUARDRAIL, KindNormalizer.normalize("guardrail"));
        assertEquals(KindNormalizer.PLAN, KindNormalizer.normalize("plan"));
        // Open-enum extension values: the op name is the single kind source, so reasoning/handoff
        // are accepted values.
        assertEquals(KindNormalizer.REASONING, KindNormalizer.normalize("reasoning"));
        assertEquals(KindNormalizer.HANDOFF, KindNormalizer.normalize("handoff"));
        assertEquals(KindNormalizer.MEMORY, KindNormalizer.normalize("memory.read"));
        assertEquals(KindNormalizer.MEMORY, KindNormalizer.normalize("memory.write"));
    }

    @Test
    void unknownOrAbsentNormalizesToNull() {
        assertNull(KindNormalizer.normalize("some_custom_op"));
        assertNull(KindNormalizer.normalize(""));
        assertNull(KindNormalizer.normalize(null));
    }

    @Test
    void attributeAwareForm_onlyMcpDiscrimination_noTessaryOverlay() {
        // The ONE standard attribute discriminator: execute_tool + gen_ai.tool.type=extension → mcp.
        assertEquals(
                KindNormalizer.MCP,
                KindNormalizer.normalize("execute_tool", Map.of(GenAiAttributes.TOOL_TYPE, "extension")));
        // tessary.* attributes contribute nothing to kind — only the op name and gen_ai.tool.type are read.
        assertEquals(
                KindNormalizer.AGENT,
                KindNormalizer.normalize("invoke_agent", Map.of("tessary.handoff.to", "billing")));
        assertEquals(KindNormalizer.LLM, KindNormalizer.normalize("chat", Map.of("tessary.kind", "reranker")));
        assertEquals(
                KindNormalizer.TOOL,
                KindNormalizer.normalize("execute_tool", Map.of("tessary.mcp.server", "travel-mcp")));
        // no attributes → falls back to the op-name mapping.
        assertEquals(KindNormalizer.LLM, KindNormalizer.normalize("chat", Map.of()));
        assertEquals(KindNormalizer.TOOL, KindNormalizer.normalize("execute_tool", Map.of()));
        assertEquals(KindNormalizer.LLM, KindNormalizer.normalize("chat", null));
    }
}
