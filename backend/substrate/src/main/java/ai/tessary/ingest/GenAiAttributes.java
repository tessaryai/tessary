// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

/**
 * The canonical attribute vocabulary of the data model: OpenTelemetry GenAI semantic conventions
 * ({@code gen_ai.*}) plus a small namespaced {@code tessary.*} overlay used <em>only where gen_ai is
 * silent</em>. This is the single Java surface every emit/read/normalize site keys on, so "conform to
 * the schema doc" is enforceable in one place rather than via string literals scattered across adapters.
 *
 * <p>Authority: see {@code docs/reference/trace-schema.md} for the full schema + the OpenInference
 * ({@code llm.*}/{@code openinference.*}) → {@code gen_ai.*} mapping table this class realizes. Keys here
 * are verified against the OTel GenAI semantic conventions (semconv v1.41.x, GenAI conventions in
 * <em>Development</em>) and the Arize OpenInference spec; never invent an attribute where a standard
 * {@code gen_ai.*} one exists.
 *
 * <p><b>{@code gen_ai.system} vs {@code gen_ai.provider.name}:</b> OTel renamed the provider discriminator
 * from {@code gen_ai.system} to {@code gen_ai.provider.name}. The evals plugin's Path-A reader still keys on
 * {@code gen_ai.system}, so the JSONL export ({@link ai.tessary.ingest.export.TraceSpanMapper}) emits
 * {@code gen_ai.system}; the live OTLP/in-process span path ({@code sandbox.AgentSpanTelemetry}) emits the newer
 * {@code gen_ai.provider.name}. Both names are kept here so call sites are explicit about which they mean.
 */
public final class GenAiAttributes {

    private GenAiAttributes() {}

    // ----- gen_ai.* (canonical; OTel GenAI semconv) -------------------------------------------------

    /** {@code gen_ai.operation.name} — the operation discriminator ({@link #OP_CHAT} etc.). */
    public static final String OPERATION_NAME = "gen_ai.operation.name";
    /** {@code gen_ai.system} — provider discriminator; the name the evals plugin Path-A reader keys on. */
    public static final String SYSTEM = "gen_ai.system";
    /** {@code gen_ai.provider.name} — the renamed provider discriminator (OTel ≥ v1.37); OTLP/live-span path. */
    public static final String PROVIDER_NAME = "gen_ai.provider.name";
    /** {@code gen_ai.request.model} — the requested model id. */
    public static final String REQUEST_MODEL = "gen_ai.request.model";
    /** {@code gen_ai.input.messages} — JSON-encoded {@code [{role, parts:[{type,content}]}]}. */
    public static final String INPUT_MESSAGES = "gen_ai.input.messages";
    /** {@code gen_ai.output.messages} — JSON-encoded output messages (carry {@code finish_reason}). */
    public static final String OUTPUT_MESSAGES = "gen_ai.output.messages";
    /** {@code gen_ai.usage.input_tokens} — prompt token count (semconv: includes cached tokens). */
    public static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    /** {@code gen_ai.usage.output_tokens} — completion token count. */
    public static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    /** {@code gen_ai.usage.total_tokens} — total token count (input + output). */
    public static final String USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";
    /** {@code gen_ai.usage.cache_read.input_tokens} — cached input tokens read (semconv registry, Development). */
    public static final String USAGE_CACHE_READ_INPUT_TOKENS = "gen_ai.usage.cache_read.input_tokens";
    /** {@code gen_ai.usage.cache_creation.input_tokens} — input tokens written to cache (semconv registry, Development). */
    public static final String USAGE_CACHE_CREATION_INPUT_TOKENS = "gen_ai.usage.cache_creation.input_tokens";
    /**
     * {@code gen_ai.usage.reasoning_tokens} — tokens spent on reasoning/thinking, billed separately from
     * output by every provider that reports them. Its own typed bucket on {@code span}, because folding it
     * into output makes a reasoning-heavy call indistinguishable from a verbose one on both the token and
     * the cost surface.
     */
    public static final String USAGE_REASONING_TOKENS = "gen_ai.usage.reasoning_tokens";
    /** OpenInference's spelling of the reasoning bucket, merged onto the canonical key at the OI edge. */
    public static final String OI_TOKEN_COUNT_REASONING = "llm.token_count.completion_details.reasoning";

    // ----- producer-reported cost -------------------------------------------------------------------
    // Read by the v2 ingest pricer (substrate-model.md §6.5 rule 1): a cost the producer computed is
    // stored verbatim and never repriced, because it is a fact about what the call was billed at. No
    // mapper change carries these — the OTLP mapper already flattens every span attribute into
    // RawEntry.metadata, so naming them here is what makes them readable rather than incidental.

    /** {@code gen_ai.usage.cost} — the producer's total cost for the call, in USD. */
    public static final String USAGE_COST = "gen_ai.usage.cost";
    /** {@code gen_ai.usage.total_cost} — the OpenLLMetry/Traceloop spelling of the same total. */
    public static final String USAGE_TOTAL_COST = "gen_ai.usage.total_cost";
    /** {@code llm.cost.total} — the OpenInference spelling of the same total. */
    public static final String OI_COST_TOTAL = "llm.cost.total";
    /** {@code llm.cost.prompt} — OpenInference per-bucket input cost. */
    public static final String OI_COST_PROMPT = "llm.cost.prompt";
    /** {@code llm.cost.completion} — OpenInference per-bucket output cost. */
    public static final String OI_COST_COMPLETION = "llm.cost.completion";
    /** {@code llm.cost.prompt_details.cache_read} — OpenInference per-bucket cache-read cost. */
    public static final String OI_COST_CACHE_READ = "llm.cost.prompt_details.cache_read";
    /** {@code llm.cost.prompt_details.cache_write} — OpenInference per-bucket cache-write cost. */
    public static final String OI_COST_CACHE_WRITE = "llm.cost.prompt_details.cache_write";
    /** {@code gen_ai.tool.name} — the invoked tool/function name. */
    public static final String TOOL_NAME = "gen_ai.tool.name";
    /** {@code gen_ai.tool.call.id} — the tool-call correlation id. */
    public static final String TOOL_CALL_ID = "gen_ai.tool.call.id";

    // The gen_ai.evaluation.* event keys are deliberately absent. The substrate stores no feedback: it has
    // no row for it, no read surface serves it, and the ingest path that used to materialize one is gone.
    // A producer that still emits gen_ai.evaluation.result events is not an error — the events simply ride
    // the span's attribute bag into span_payload like anything else the platform does not type.

    /** {@code gen_ai.response.id} — the provider's id for this generation's response. */
    public static final String RESPONSE_ID = "gen_ai.response.id";

    // gen_ai.operation.name enum values (OTel; open/Development enum — see KindNormalizer).
    public static final String OP_CHAT = "chat";
    public static final String OP_TEXT_COMPLETION = "text_completion";
    public static final String OP_GENERATE_CONTENT = "generate_content";
    public static final String OP_EMBEDDINGS = "embeddings";
    public static final String OP_EXECUTE_TOOL = "execute_tool";
    public static final String OP_RETRIEVAL = "retrieval";
    public static final String OP_CREATE_AGENT = "create_agent";
    public static final String OP_INVOKE_AGENT = "invoke_agent";
    public static final String OP_INVOKE_WORKFLOW = "invoke_workflow";
    /** {@code gen_ai.operation.name = rerank} — a reranker call (OpenInference RERANKER). */
    public static final String OP_RERANK = "rerank";
    /** {@code gen_ai.operation.name = guardrail} — a guardrail check (OpenInference GUARDRAIL). */
    public static final String OP_GUARDRAIL = "guardrail";
    /** {@code gen_ai.operation.name = plan} — an agent planning step. */
    public static final String OP_PLAN = "plan";

    /** {@code gen_ai.tool.type} — {@code extension} marks an MCP/plugin tool (vs a first-party function). */
    public static final String TOOL_TYPE = "gen_ai.tool.type";

    /**
     * {@code gen_ai.conversation.id} — the standard GenAI conversation/thread id. Materializes a
     * {@code kind='conversation'} context between the session and its turns, or the root
     * context itself when the producer states no {@code session.id}.
     */
    public static final String CONVERSATION_ID = "gen_ai.conversation.id";

    // ----- Standard non-gen_ai attributes adopted as-is (do not re-namespace) -----------------------

    /** {@code session.id} — conversation/thread id; standard OTel attribute (OpenInference uses it bare). */
    public static final String SESSION_ID = "session.id";

    // DEPLOYMENT_ENVIRONMENT_NAME ("deployment.environment.name") was read here to scope an ingested
    // span to an Environment row. Track A removed the Environment concept, so nothing structural reads
    // it any more. The attribute is still ACCEPTED — a producer that ships it is not rejected, and it
    // survives as an ordinary attribute in the span's payload — it simply no longer scopes anything.
    // The ingestion contract says so explicitly; do not re-add a structural read without a scope to
    // put it in.

    /**
     * {@code error.type} — the standard OTel attribute for the CLASS of an error: a low-cardinality name
     * ("TimeoutError", "HTTPError"), never a message. It is what {@code span.error_type} reads, and the
     * reason that column can be faceted at all; a producer that ships none falls back to a capped
     * signature of its status message. The prose itself lives in {@code span.error_message}.
     */
    public static final String ERROR_TYPE = "error.type";

    /**
     * {@code statusMessage} — the OTLP span status description, as the ingest mappers spell it in the
     * attribute bag. Free prose, up to kilobytes of it, and the source of {@code span.error_message}.
     */
    public static final String STATUS_MESSAGE = "statusMessage";

    // ----- tessary.* overlay (ONLY where gen_ai is silent) ------------------------------------------
    // These name structure the OTel GenAI agent conventions do not yet standardize. They are seeded as
    // named constants so the agent-native substrate builds on one vocabulary; each is chosen so it can
    // later be RENAMED to the emerging standard rather than refactored. Some are defined in the doc but
    // not yet populated into storage. See docs/reference/trace-schema.md §Overlay.

    /**
     * {@code tessary.call_site.id} — the explicit call-site binding (the dotted form roots the expandable
     * {@code tessary.call_site.*} namespace); the authoritative call site, and the ONLY spelling read. Set
     * by a producer's call-site instrumentation <em>or</em> by any OTLP producer
     * by hand (one span attribute, no SDK required). See docs/reference/ingestion-contract/README.md.
     */
    public static final String TESSARY_CALL_SITE_ID = "tessary.call_site.id";
    /**
     * {@code tessary.sdk} — provenance marker a producer stamps on its traffic. Kept on the persisted
     * span attributes for lineage; call-site binding is the explicit {@code tessary.call_site.id} tag for
     * all senders, so nothing branches on this marker.
     */
    public static final String TESSARY_SDK = "tessary.sdk";

    // Hierarchy correlation rides ONLY the standard attributes (session.id, gen_ai.conversation.id,
    // user.id) — there are no tessary.* correlation overlays.

    // ----- tessary.upstream.* — selective-pull provenance ------------------------------
    // Provenance for a row landed by an on-demand upstream pull (vs. native ingest), carried INTO each
    // RawEntry's metadata at the pull runner so it lands in the existing observation.metadata_json with
    // zero substrate-schema change. ADDITIVE ONLY: never read on any grading/read path — synced data
    // stays indistinguishable from native ingest. The authoritative coverage record is the pull_job row.

    /** {@code tessary.upstream.source_id} — the ingestion_source this row was pulled from. */
    public static final String TESSARY_UPSTREAM_SOURCE_ID = "tessary.upstream.source_id";
    /** {@code tessary.upstream.provider} — the upstream vendor (langfuse / phoenix / fake / …). */
    public static final String TESSARY_UPSTREAM_PROVIDER = "tessary.upstream.provider";
    /** {@code tessary.upstream.pull_job_id} — the pull job that landed this row (coverage handle). */
    public static final String TESSARY_UPSTREAM_PULL_JOB_ID = "tessary.upstream.pull_job_id";

    // ----- OpenLLMetry/Traceloop (gen_ai.prompt.N.* / gen_ai.completion.N.*) — INGEST-ONLY flattened keys ---
    // Not canonical message keys: the OpenLLMetry/Traceloop OTLP exporter encodes messages as indexed,
    // flattened span attributes (gen_ai.prompt.0.role, gen_ai.prompt.0.content, gen_ai.completion.0.role, …)
    // rather than the canonical gen_ai.input.messages / gen_ai.output.messages JSON arrays above. The OTLP
    // mapper reads-and-reconstructs them into the same ordered [{role, content}] form at the edge (and ONLY
    // when the canonical structured array is absent — the structured form always wins). These are NOT OTel
    // semconv; do not treat them as canonical. See docs/reference/trace-schema.md §Flattened message encoding.

    /** {@code gen_ai.prompt.} — Traceloop indexed input-message key prefix ({@code gen_ai.prompt.N.role|content}). */
    public static final String TRACELOOP_PROMPT_PREFIX = "gen_ai.prompt.";
    /** {@code gen_ai.completion.} — Traceloop indexed output-message key prefix ({@code gen_ai.completion.N.role|content}). */
    public static final String TRACELOOP_COMPLETION_PREFIX = "gen_ai.completion.";
    /** Indexed-message sub-key: the message role ({@code gen_ai.prompt.N.role}). */
    public static final String TRACELOOP_MESSAGE_ROLE = "role";
    /** Indexed-message sub-key: the message content ({@code gen_ai.prompt.N.content}). */
    public static final String TRACELOOP_MESSAGE_CONTENT = "content";

    // ----- OpenInference (llm.* / openinference.*) — INGEST-ONLY input keys -------------------------
    // Not canonical: these are the foreign vocabulary the OpenInferenceNormalizer reads and rewrites to the
    // gen_ai.* keys above. Verified against the Arize OpenInference semantic-conventions spec.

    /** {@code openinference.span.kind} — OI operation discriminator (LLM/AGENT/TOOL/RETRIEVER/CHAIN/…). */
    public static final String OI_SPAN_KIND = "openinference.span.kind";
    /** {@code llm.model_name}. */
    public static final String OI_MODEL_NAME = "llm.model_name";
    /** {@code llm.system} — OI provider/system. */
    public static final String OI_SYSTEM = "llm.system";
    /** {@code llm.provider} — OI hosting provider (when distinct from system). */
    public static final String OI_PROVIDER = "llm.provider";
    /** {@code llm.input_messages}. */
    public static final String OI_INPUT_MESSAGES = "llm.input_messages";
    /** {@code llm.output_messages}. */
    public static final String OI_OUTPUT_MESSAGES = "llm.output_messages";
    /** {@code message.role} — OI message sub-key. */
    public static final String OI_MESSAGE_ROLE = "message.role";
    /** {@code message.content} — OI message sub-key (scalar text). */
    public static final String OI_MESSAGE_CONTENT = "message.content";
    /** {@code message.contents} — OI multimodal message sub-key (array of typed parts). */
    public static final String OI_MESSAGE_CONTENTS = "message.contents";
    /** {@code message_content.type} — OI multimodal part type. */
    public static final String OI_MESSAGE_CONTENT_TYPE = "message_content.type";
    /** {@code message_content.text} — OI multimodal part text. */
    public static final String OI_MESSAGE_CONTENT_TEXT = "message_content.text";
    /** {@code message.tool_calls} — OI output tool-call array sub-key. */
    public static final String OI_MESSAGE_TOOL_CALLS = "message.tool_calls";
    /** {@code llm.token_count.prompt}. */
    public static final String OI_TOKEN_COUNT_PROMPT = "llm.token_count.prompt";
    /** {@code llm.token_count.completion}. */
    public static final String OI_TOKEN_COUNT_COMPLETION = "llm.token_count.completion";
    /** {@code llm.token_count.total}. */
    public static final String OI_TOKEN_COUNT_TOTAL = "llm.token_count.total";
    /** {@code tool_call.function.name}. */
    public static final String OI_TOOL_CALL_FUNCTION_NAME = "tool_call.function.name";
    /** {@code tool_call.function.arguments}. */
    public static final String OI_TOOL_CALL_FUNCTION_ARGS = "tool_call.function.arguments";
    /** {@code tool_call.id}. */
    public static final String OI_TOOL_CALL_ID = "tool_call.id";

    // OpenInference RETRIEVER spans flatten their retrieved passages as indexed attributes
    // (retrieval.documents.{N}.document.{id|content|score|metadata}). The write path scans the attribute
    // bag for the prefix and re-assembles them into first-class retrieved_doc rows.
    /** {@code retrieval.documents.} — the indexed-document attribute prefix on an OI retriever span. */
    public static final String OI_RETRIEVAL_DOCUMENTS_PREFIX = "retrieval.documents.";
    /** sub-key: {@code .document.id} (the retrieved doc/chunk id). */
    public static final String OI_DOC_ID_SUFFIX = ".document.id";
    /** sub-key: {@code .document.content} (the retrieved passage text). */
    public static final String OI_DOC_CONTENT_SUFFIX = ".document.content";
    /** sub-key: {@code .document.score} (the relevance score). */
    public static final String OI_DOC_SCORE_SUFFIX = ".document.score";
    /** sub-key: {@code .document.metadata} (per-document metadata, JSON). */
    public static final String OI_DOC_METADATA_SUFFIX = ".document.metadata";

    /**
     * Whether this attribute key names a token count or a cost — a quantity, never free text.
     *
     * <p>Two callers share it, and the second is why it is public. {@code IngestPricer} uses it to select
     * the keys that become the {@code span_payload.provided_usage} receipt; {@code RedactionService} uses
     * it to EXEMPT those keys from the PII regexes, because redaction runs on the drain immediately before
     * the write and a redacted number is not a number any more.
     *
     * <p>That is not hypothetical. A producer reporting {@code gen_ai.usage.cost = "0.0123456789"} as a
     * string had it rewritten to {@code [REDACTED_PHONE]} — ten digits is a phone number to a phone rule —
     * and the span then fell through spec §6.5 rule 1 to an inferred price: $0.000150 recorded for a call
     * the producer had said cost $0.0123456789, stamped {@code cost_source = 'inferred'} as though the
     * platform had computed it rather than lost it. The same attribute sent as {@code "0.0123"} survived,
     * so the corruption was a function of digit count alone. Exempting the keys is safe by their own
     * contract: every one is defined as an integer count or a decimal amount, so none can carry PII.
     */
    public static boolean isUsageOrCostKey(String key) {
        return key.startsWith("gen_ai.usage.") || key.startsWith("llm.token_count.") || key.startsWith("llm.cost.");
    }

    /**
     * Map an {@code openinference.span.kind} value to the canonical {@code gen_ai.operation.name}.
     * Unknown/blank kinds return {@code null} (kind unknown), mirroring {@link KindNormalizer}'s open-enum
     * contract. CHAIN maps to {@code invoke_workflow} (a chain is a coordinator of steps);
     * EMBEDDING/RERANKER map to {@code embeddings}/{@code retrieval} (both normalize to retrieval kind).
     *
     * @param spanKind the raw OI span kind (e.g. {@code "LLM"}, {@code "AGENT"}); case-insensitive; nullable
     * @return the gen_ai operation name, or {@code null} when unknown
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
            // GUARDRAIL / EVALUATOR / PROMPT have no gen_ai operation analogue — leave kind unknown.
            default -> null;
        };
    }
}
