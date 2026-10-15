// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

/**
 * The attribute vocabulary: OTel GenAI semconv ({@code gen_ai.*}) plus a {@code tessary.*} overlay only where gen_ai is
 * silent; see devdocs/reference/trace-schema.md. Both provider discriminators are kept: the evals plugin's reader and
 * the JSONL export key on {@code gen_ai.system}, the live span path emits the newer {@code gen_ai.provider.name}.
 */
public final class GenAiAttributes {

    private GenAiAttributes() {}

    public static final String OPERATION_NAME = "gen_ai.operation.name";
    /** The name the evals plugin's Path-A reader keys on. */
    public static final String SYSTEM = "gen_ai.system";
    /** OTel v1.37+; the live span path. */
    public static final String PROVIDER_NAME = "gen_ai.provider.name";
    public static final String REQUEST_MODEL = "gen_ai.request.model";
    public static final String INPUT_MESSAGES = "gen_ai.input.messages";
    public static final String OUTPUT_MESSAGES = "gen_ai.output.messages";
    /** Semconv: includes cached tokens. */
    public static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String USAGE_CACHE_READ_INPUT_TOKENS = "gen_ai.usage.cache_read.input_tokens";
    public static final String USAGE_CACHE_CREATION_INPUT_TOKENS = "gen_ai.usage.cache_creation.input_tokens";
    /** Its own bucket, or a reasoning-heavy call would be indistinguishable from a verbose one on tokens and cost. */
    public static final String USAGE_REASONING_TOKENS = "gen_ai.usage.reasoning_tokens";
    public static final String OI_TOKEN_COUNT_REASONING = "llm.token_count.completion_details.reasoning";

    // Producer-reported cost: stored verbatim by the ingest pricer and never repriced.

    public static final String USAGE_COST = "gen_ai.usage.cost";
    public static final String USAGE_TOTAL_COST = "gen_ai.usage.total_cost";
    public static final String OI_COST_TOTAL = "llm.cost.total";
    public static final String OI_COST_PROMPT = "llm.cost.prompt";
    public static final String OI_COST_COMPLETION = "llm.cost.completion";
    public static final String OI_COST_CACHE_READ = "llm.cost.prompt_details.cache_read";
    public static final String OI_COST_CACHE_WRITE = "llm.cost.prompt_details.cache_write";
    public static final String TOOL_NAME = "gen_ai.tool.name";
    public static final String TOOL_CALL_ID = "gen_ai.tool.call.id";

    // gen_ai.evaluation.* is deliberately absent: the substrate stores no feedback, and such events ride the attribute
    // bag like anything untyped.

    public static final String OP_CHAT = "chat";
    public static final String OP_EMBEDDINGS = "embeddings";
    public static final String OP_EXECUTE_TOOL = "execute_tool";
    public static final String OP_RETRIEVAL = "retrieval";
    public static final String OP_INVOKE_AGENT = "invoke_agent";
    public static final String OP_INVOKE_WORKFLOW = "invoke_workflow";

    /** {@code extension} marks an MCP/plugin tool. */
    public static final String TOOL_TYPE = "gen_ai.tool.type";

    /**
     * Materializes a conversation context between the session and its turns, or the root context when there is no
     * {@code session.id}.
     */
    public static final String CONVERSATION_ID = "gen_ai.conversation.id";

    public static final String SESSION_ID = "session.id";

    // deployment.environment.name is accepted but scopes nothing; do not re-add a structural read without a scope to
    // put it in.

    /**
     * The CLASS of an error, low-cardinality, so {@code span.error_type} can be faceted; prose goes to
     * {@code span.error_message}.
     */
    public static final String ERROR_TYPE = "error.type";

    /**
     * The OTLP status description as the ingest mappers spell it: free prose, the source of {@code span.error_message}.
     */
    public static final String STATUS_MESSAGE = "statusMessage";

    // tessary.* names only where OTel GenAI is silent, chosen so each can be renamed to the emerging standard.

    /** The authoritative call site and the ONLY spelling read; settable by any OTLP producer as one span attribute. */
    public static final String TESSARY_CALL_SITE_ID = "tessary.call_site.id";

    // Correlation rides ONLY the standard attributes; there are no tessary.* correlation overlays.

    // Traceloop's indexed, flattened message keys: INGEST-ONLY, not canonical. Reconstructed into [{role, content}]
    // only when the canonical array is absent.

    public static final String TRACELOOP_PROMPT_PREFIX = "gen_ai.prompt.";
    public static final String TRACELOOP_COMPLETION_PREFIX = "gen_ai.completion.";
    public static final String TRACELOOP_MESSAGE_ROLE = "role";
    public static final String TRACELOOP_MESSAGE_CONTENT = "content";

    // OpenInference keys: INGEST-ONLY, rewritten to gen_ai.* by OpenInferenceNormalizer.

    public static final String OI_SPAN_KIND = "openinference.span.kind";
    public static final String OI_MODEL_NAME = "llm.model_name";
    public static final String OI_SYSTEM = "llm.system";
    /** OI hosting provider, when distinct from system. */
    public static final String OI_PROVIDER = "llm.provider";
    public static final String OI_INPUT_MESSAGES = "llm.input_messages";
    public static final String OI_OUTPUT_MESSAGES = "llm.output_messages";
    public static final String OI_MESSAGE_ROLE = "message.role";
    public static final String OI_MESSAGE_CONTENT = "message.content";
    /** Multimodal: an array of typed parts. */
    public static final String OI_MESSAGE_CONTENTS = "message.contents";
    public static final String OI_MESSAGE_CONTENT_TEXT = "message_content.text";
    public static final String OI_MESSAGE_TOOL_CALLS = "message.tool_calls";
    public static final String OI_TOKEN_COUNT_PROMPT = "llm.token_count.prompt";
    public static final String OI_TOKEN_COUNT_COMPLETION = "llm.token_count.completion";
    public static final String OI_TOKEN_COUNT_TOTAL = "llm.token_count.total";
    public static final String OI_TOOL_CALL_FUNCTION_NAME = "tool_call.function.name";
    public static final String OI_TOOL_CALL_FUNCTION_ARGS = "tool_call.function.arguments";
    public static final String OI_TOOL_CALL_ID = "tool_call.id";

    // OI RETRIEVER spans flatten passages as retrieval.documents.{N}.document.*; the write path re-assembles them into
    // retrieved_doc rows.
    public static final String OI_RETRIEVAL_DOCUMENTS_PREFIX = "retrieval.documents.";
    public static final String OI_DOC_ID_SUFFIX = ".document.id";
    public static final String OI_DOC_CONTENT_SUFFIX = ".document.content";
    public static final String OI_DOC_SCORE_SUFFIX = ".document.score";
    /** Per-document metadata, JSON. */
    public static final String OI_DOC_METADATA_SUFFIX = ".document.metadata";

    /**
     * A quantity, never free text. Public because {@code RedactionService} EXEMPTS these keys from PII regexes: a
     * ten-digit string cost was redacted as a phone number and the span silently fell back to an inferred price. Safe,
     * since every such key is a count or an amount.
     */
    public static boolean isUsageOrCostKey(String key) {
        return key.startsWith("gen_ai.usage.") || key.startsWith("llm.token_count.") || key.startsWith("llm.cost.");
    }

    /**
     * Unknown or blank returns null, as {@link KindNormalizer}'s open enum does. CHAIN maps to {@code invoke_workflow}.
     */
    public static @org.jspecify.annotations.Nullable String operationNameForSpanKind(
            @org.jspecify.annotations.Nullable String spanKind) {
        if (spanKind == null || spanKind.isBlank()) return null;
        return switch (spanKind.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LLM" -> OP_CHAT;
            case "AGENT" -> OP_INVOKE_AGENT;
            case "TOOL" -> OP_EXECUTE_TOOL;
            case "RETRIEVER", "RERANKER" -> OP_RETRIEVAL;
            case "EMBEDDING" -> OP_EMBEDDINGS;
            case "CHAIN" -> OP_INVOKE_WORKFLOW;
            // GUARDRAIL, EVALUATOR and PROMPT have no gen_ai analogue.
            default -> null;
        };
    }
}
