// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps OTel GenAI {@code gen_ai.operation.name} to the internal operation kind, shared by every ingest adapter. The
 * enum is open and Development, so an unknown value normalizes to {@code null} rather than throwing; consumers must
 * tolerate a null kind.
 */
public final class KindNormalizer {

    private KindNormalizer() {}

    /** A (possibly multi-turn) agent invocation — the aggregate "outcome" unit. */
    public static final String AGENT = "agent";

    public static final String LLM = "llm";
    public static final String TOOL = "tool";
    public static final String RETRIEVAL = "retrieval";
    /** A coordinator fanning out multiple agents. */
    public static final String WORKFLOW = "workflow";

    public static final String REASONING = "reasoning";
    /** An MCP / plugin tool call ({@code execute_tool} + {@code gen_ai.tool.type = extension}). */
    public static final String MCP = "mcp";
    /** An embeddings call (distinct from retrieval). */
    public static final String EMBEDDING = "embedding";

    public static final String RERANKER = "reranker";
    public static final String GUARDRAIL = "guardrail";
    public static final String PLAN = "plan";
    public static final String MEMORY = "memory";
    /**
     * Usually derivable structurally (an {@code execute_tool} transfer span followed by {@code invoke_agent}), so
     * producers rarely state it.
     */
    public static final String HANDOFF = "handoff";
    /** A generic/unrecognized step (has an operation name we don't map, but isn't nothing). */
    public static final String STEP = "step";

    /** The operation name is the single kind source; there is no {@code tessary.*} kind overlay. */
    public static @Nullable String normalize(@Nullable String operationName) {
        if (operationName == null || operationName.isBlank()) return null;
        return switch (operationName) {
            case "chat", "text_completion", "generate_content" -> LLM;
            case "invoke_agent", "create_agent" -> AGENT;
            case "invoke_workflow" -> WORKFLOW;
            case "execute_tool" -> TOOL;
            case "embeddings" -> EMBEDDING;
            case "retrieval" -> RETRIEVAL;
            case "rerank" -> RERANKER;
            case "guardrail" -> GUARDRAIL;
            case "plan" -> PLAN;
            case "reasoning" -> REASONING;
            case "handoff" -> HANDOFF;
            default -> operationName.contains("memory") ? MEMORY : null;
        };
    }

    /**
     * Adds the one standard attribute discriminator: an {@code execute_tool} with {@code gen_ai.tool.type = extension}
     * is an {@code mcp} call.
     */
    public static @Nullable String normalize(
            @Nullable String operationName, Map<String, ? extends @Nullable Object> attrs) {
        if ("execute_tool".equals(operationName) && "extension".equals(str(attrs, GenAiAttributes.TOOL_TYPE))) {
            return MCP;
        }
        return normalize(operationName);
    }

    private static @Nullable String str(Map<String, ? extends @Nullable Object> attrs, String key) {
        Object v = attrs.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * The canonical operation name per kind, so the exporter does not hard-code {@code "chat"}. A null or unknown kind
     * defaults to {@code chat}.
     */
    public static String operationName(@Nullable String kind) {
        if (kind == null) return GenAiAttributes.OP_CHAT;
        return switch (kind) {
            case AGENT -> GenAiAttributes.OP_INVOKE_AGENT;
            case TOOL -> GenAiAttributes.OP_EXECUTE_TOOL;
            case RETRIEVAL -> GenAiAttributes.OP_RETRIEVAL;
            case WORKFLOW -> GenAiAttributes.OP_INVOKE_WORKFLOW;
            default -> GenAiAttributes.OP_CHAT;
        };
    }
}
