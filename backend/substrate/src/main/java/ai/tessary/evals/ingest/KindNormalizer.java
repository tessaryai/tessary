// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Normalizes an OTel GenAI {@code gen_ai.operation.name} (semconv v1.37.0; open, Development enum) into the
 * platform's internal operation <em>kind</em> — a vendor-neutral discriminator distinct from any provider's
 * observation "type". This is the one place the OTel standard maps to our canonical {@link RawEntry#operationKind}.
 *
 * <p>Shared by every ingest adapter at the {@link RawEntry} boundary: the Langfuse pull-adapter reads the OTel
 * name out of the observation's round-tripped {@code metadata.attributes}, and a future OTLP receiver reads it
 * natively off the span — both feed it through here, so KIND means the same thing regardless of source.
 *
 * <p>The enum is open and Development, so an unknown/custom/absent value normalizes to {@code null} (kind
 * unknown) rather than throwing; every consumer must tolerate a null kind by degrading to its per-entry default.
 */
public final class KindNormalizer {

    private KindNormalizer() {}

    /** A (possibly multi-turn) agent invocation — the aggregate "outcome" unit. */
    public static final String AGENT = "agent";
    /** A single LLM call (one turn). */
    public static final String LLM = "llm";
    /** A tool execution. */
    public static final String TOOL = "tool";
    /** A retrieval / embeddings call. */
    public static final String RETRIEVAL = "retrieval";
    /** A coordinator fanning out multiple agents. */
    public static final String WORKFLOW = "workflow";
    /** A reasoning / thinking LLM turn ({@code gen_ai.operation.name = reasoning}, open-enum extension). */
    public static final String REASONING = "reasoning";
    /** An MCP / plugin tool call ({@code execute_tool} + {@code gen_ai.tool.type = extension}). */
    public static final String MCP = "mcp";
    /** An embeddings call (distinct from retrieval). */
    public static final String EMBEDDING = "embedding";
    /** A reranker call. */
    public static final String RERANKER = "reranker";
    /** A guardrail check (input/output moderation). */
    public static final String GUARDRAIL = "guardrail";
    /** An agent planning step. */
    public static final String PLAN = "plan";
    /** A long-term memory read/write. */
    public static final String MEMORY = "memory";
    /**
     * A lateral control transfer between agents ({@code gen_ai.operation.name = handoff}, open-enum
     * extension). Usually derivable structurally instead — an {@code execute_tool} transfer span followed
     * by an {@code invoke_agent} span (ingestion-contract §Handoffs) — so producers rarely state it.
     */
    public static final String HANDOFF = "handoff";
    /** A generic/unrecognized step (has an operation name we don't map, but isn't nothing). */
    public static final String STEP = "step";

    /**
     * Map a {@code gen_ai.operation.name} to the normalized kind, or {@code null} when unknown/absent.
     * The operation name is the SINGLE kind source: the enum is open/Development, so {@code rerank}/
     * {@code guardrail}/{@code plan}/{@code retrieval}/{@code reasoning}/{@code handoff} are accepted
     * extension values — there is no {@code tessary.*} kind overlay.
     *
     * @param operationName the raw OTel operation name (e.g. {@code chat}, {@code invoke_agent}); nullable
     */
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
     * Attribute-aware normalization (the OTLP edge): the op-name mapping plus the ONE standard attribute
     * discriminator — an {@code execute_tool} with {@code gen_ai.tool.type = extension} is an {@code mcp}
     * call. No {@code tessary.*} attribute participates in kind resolution.
     *
     * @param operationName the (possibly foreign-normalized) OTel operation name; nullable
     * @param attrs the span's flattened attribute bag (reads {@code gen_ai.tool.type}); may be null
     */
    public static @Nullable String normalize(
            @Nullable String operationName, @Nullable Map<String, ? extends @Nullable Object> attrs) {
        if ("execute_tool".equals(operationName) && "extension".equals(str(attrs, GenAiAttributes.TOOL_TYPE))) {
            return MCP;
        }
        return normalize(operationName);
    }

    private static @Nullable String str(@Nullable Map<String, ? extends @Nullable Object> attrs, String key) {
        if (attrs == null) return null;
        Object v = attrs.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * The representative {@code gen_ai.operation.name} for an internal kind — the inverse of {@link #normalize},
     * used by the export ({@code TraceSpanMapper}) so a re-emitted span carries the truthful operation name
     * rather than a hard-coded {@code "chat"}. The mapping is many-to-one (e.g. {@code chat}/{@code text_completion}
     * both ⇒ {@code llm}), so this picks the canonical representative per kind. A {@code null}/unknown kind
     * defaults to {@code chat} — the single-LLM-call assumption the exporter already made.
     *
     * @param kind the internal kind ({@link #AGENT}/{@link #LLM}/…); nullable
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
