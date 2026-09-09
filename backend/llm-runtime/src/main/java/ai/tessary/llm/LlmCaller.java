// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.ingest.export.TraceSpanMapper;
import ai.tessary.llm.StructuredOutput.StructuredOutputException;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.open.errors.JudgeError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pricing.PlatformCallPricer;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.bedrock.BedrockTokenUsage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single instrumented entry point for every LLM chat call in the app. Wraps one
 * {@code chat()} in an OpenTelemetry {@code gen_ai.*} CLIENT span (the shape the Alloy
 * filter forwards to Langfuse), stamps token usage + Bedrock cost/cache details, and
 * applies the {@link LlmPacer} (rate-limit + 429 back-off). Every caller — the judge,
 * the deterministic-grader code generator, the agentic sandboxes — goes through here, so they
 * all reach Langfuse identically and no future caller can silently skip tracing.
 *
 * <p>Trace grouping is the caller's decision: pass the run span's {@link Context} as
 * {@code parentTrace} to nest under it, or {@code null} for a fresh trace. {@code null}
 * deliberately uses {@code setNoParent()} — it must NOT inherit an ambient HTTP-server
 * span, which Alloy drops, orphaning the observation.
 */
@Component
public class LlmCaller {

    private static final Logger log = LoggerFactory.getLogger(LlmCaller.class);
    // Deliberately static, not the JacksonConfig bean: used only by the private static
    // span-JSON helpers below (serialization-only, so mapper strictness is irrelevant).
    private static final ObjectMapper SPAN_MAPPER = new ObjectMapper();
    /** {@code <n>ms|s|m|h} — the cache-TTL duration form parsed for the back-off guard. */
    private static final Pattern TTL_DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)");

    private final LlmPacer pacer;
    private final Tracer tracer;
    private final ObjectMapper mapper;

    /**
     * Prompt-cache TTL in millis, derived from {@code tessary.grader.cache.ttl} (default 5m).
     * A 429 back-off that, cumulatively, outlives this window likely lets the warm prompt
     * cache expire — so a later grader on the unit that should have been a cache <i>read</i>
     * silently becomes a re-<i>write</i> (billed at the premium). We can't observe the actual
     * cache state, so this is the conservative proxy used only to log + count the likely event.
     */
    private final long cacheTtlMs;

    /**
     * Counts judge calls whose cumulative 429 back-off exceeded the cache TTL — i.e. the
     * warm prompt cache likely expired mid-retry, turning an intended cache read into a
     * re-write. Tuning signal only (no behaviour change); correlate against the
     * cache hit-rate/amortization telemetry and {@code tessary.judge.duplicate_verdict}.
     */
    private final LongCounter cacheLikelyExpiryCounter;

    /**
     * Counts {@link StructuredOutput.Mode#TOOL_CALL} responses that arrived as prose instead of a
     * tool call. We still salvage those (the text usually holds the JSON anyway), so without this
     * counter a model quietly ignoring its forced tool choice would be invisible — the requests would
     * succeed and only the reliability would drop. Watch it when adding a model or flipping
     * {@code forced_tool_choice}.
     */
    private final LongCounter structuredToolMissCounter;

    /**
     * Where each completed call's tokens and cost are booked, per lane. Null only on the
     * ledger-less constructor below — the span still carries the same numbers either way, so a call
     * made without an accountant is traced and priced exactly as before, just not tallied.
     */
    private final @Nullable LlmUsageAccountant accountant;

    /**
     * Where a completed call's rates come from: the versioned {@code price_book}, the same one an ingested
     * span is priced from. Null only on the ledger-less constructor below — the book is a table, so a
     * caller standing this seam up outside a Spring context has no datasource to read it from and its
     * calls are recorded unpriced rather than priced from a second, stale copy of the rates.
     */
    private final @Nullable PlatformCallPricer pricer;

    /**
     * The ledger-less overload: an LLM call path with no usage accounting and no pricing, for callers
     * standing the seam up outside a Spring context (there is no datasource behind
     * {@link LlmUsageAccountant} or {@link PlatformCallPricer} there).
     */
    public LlmCaller(LlmPacer pacer, OpenTelemetry openTelemetry, ObjectMapper mapper, String cacheTtl) {
        this(pacer, openTelemetry, mapper, cacheTtl, null, null);
    }

    @Autowired
    public LlmCaller(
            LlmPacer pacer,
            OpenTelemetry openTelemetry,
            ObjectMapper mapper,
            @Value("${tessary.grader.cache.ttl:5m}") String cacheTtl,
            @Nullable LlmUsageAccountant accountant,
            @Nullable PlatformCallPricer pricer) {
        this.accountant = accountant;
        this.pricer = pricer;
        this.pacer = pacer;
        this.tracer = openTelemetry.getTracer("ai.tessary.llm");
        this.mapper = mapper;
        this.cacheTtlMs = parseCacheTtlMs(cacheTtl);
        this.structuredToolMissCounter = openTelemetry
                .getMeter("ai.tessary.llm")
                .counterBuilder("tessary.llm.structured_output.tool_miss")
                .setDescription("Tool-mode structured-output calls answered with prose instead of a tool call")
                .setUnit("{call}")
                .build();
        this.cacheLikelyExpiryCounter = openTelemetry
                .getMeter("ai.tessary.llm")
                .counterBuilder("tessary.cache.likely_expiry")
                .setDescription("Judge calls whose cumulative 429 back-off exceeded the prompt-cache TTL, so the "
                        + "warm cache likely expired and the intended read became a re-write")
                .setUnit("{call}")
                .build();
    }

    /**
     * Parse the configured cache TTL to millis for the back-off guard. Accepts the
     * {@code <n>ms|s|m|h} duration form (so the Bedrock-supported {@code 5m}/{@code 1h} map
     * exactly, and finer values are usable in tests); an unrecognised/blank value falls back
     * to 5 minutes so the guard always has a sane window to compare against.
     */
    static long parseCacheTtlMs(@Nullable String value) {
        if (value == null) return 300_000L; // 5m default
        Matcher m = TTL_DURATION.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return 300_000L; // blank/unknown → 5m default
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException overflow) {
            return 300_000L; // pathologically long digit run → 5m default
        }
        return switch (m.group(2)) {
            case "ms" -> n;
            case "s" -> n * 1_000L;
            case "h" -> n * 3_600_000L;
            default -> n * 60_000L; // "m"
        };
    }

    /**
     * One chat call's response, wall-clock latency, and what it cost.
     *
     * <p>{@code costUsd} is the tier-scaled per-bucket total from {@link PlatformCallPricer} — the SAME
     * number stamped on the Langfuse span, so per-call cost and persisted cost can never disagree. It is
     * {@code null} when no price book in force holds a rate for the model (or the tier has no published
     * factor): the pricer deliberately yields nothing rather than a guessed rate, and null is the honest
     * "unpriced" sentinel every consumer must handle explicitly rather than reading as $0.
     *
     * <p>{@code priceBookVersion} names the book those rates came from, and is non-null exactly when
     * {@code costUsd} is. It rides along so the ledger row can be stamped with it: a cost is only
     * auditable if the book behind it is named, and with a manual correction book layered over the
     * vendored snapshot, which one priced a given model is not deducible from the model id alone.
     */
    public record Completion(
            ChatResponse response,
            long elapsedMs,
            @Nullable BigDecimal costUsd,
            @Nullable String priceBookVersion) {

        /** A completion before pricing — the shape {@code pacedCall} produces and every non-priced caller uses. */
        public Completion(ChatResponse response, long elapsedMs) {
            this(response, elapsedMs, null, null);
        }

        /** The same completion carrying the cost priced from its own token usage, and the book that priced it. */
        public Completion withCost(PlatformCallPricer.@Nullable PricedCall priced) {
            return priced == null
                    ? new Completion(response, elapsedMs, null, null)
                    : new Completion(response, elapsedMs, priced.total(), priced.priceBookVersion());
        }
    }

    /**
     * Cache token counts a provider reported (Bedrock, Anthropic-direct, OpenAI); nulls when
     * unavailable. {@code write} is the cache-creation bucket — null for providers like OpenAI
     * whose automatic prefix caching has no separate write charge.
     *
     * <p>{@code readOverlapsInput} captures whether the provider's reported input-token count
     * <i>includes</i> the {@code read} (cache-read) tokens. Bedrock/Anthropic report
     * non-overlapping buckets ({@code input_tokens} already excludes cache-read), so it is
     * {@code false}. OpenAI's {@code prompt_tokens} (surfaced as {@code inputTokenCount()})
     * <i>includes</i> {@code prompt_tokens_details.cached_tokens}, so it is {@code true}; cost
     * accounting must carve the cached tokens out of the input bucket to avoid billing them
     * twice (once at full input rate, once at the discounted cache-read rate).
     */
    public record CacheUsage(
            @Nullable Integer read, @Nullable Integer write, boolean readOverlapsInput) {}

    /**
     * Trace + cost + pace one chat call. {@code spanName} names the Langfuse observation;
     * {@code meta} is stamped as {@code langfuse.observation.metadata.<key>} (e.g. grader_id,
     * phase); {@code parentTrace} groups the span (null → fresh trace, see class doc).
     */
    public Completion call(
            String spanName,
            ChatModelFactory.Resolved resolved,
            ChatRequest request,
            @Nullable String projectId,
            Map<String, String> meta,
            @Nullable Context parentTrace) {
        var builder = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT);
        if (parentTrace != null) builder.setParent(parentTrace);
        else builder.setNoParent();
        Span span = builder.startSpan();
        try (var _ = span.makeCurrent()) {
            recordRequest(span, resolved, projectId, meta, request);
            Completion completion = pacedCall(spanName, resolved, request);
            Completion priced =
                    completion.withCost(recordResponse(span, completion, resolved.modelName(), resolved.tier()));
            bookUsage(resolved, projectId, priced);
            return priced;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            span.setAttribute("langfuse.observation.level", "ERROR");
            if (e.getMessage() != null) span.setAttribute("langfuse.observation.status_message", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * A {@link Completion} plus the strictly-shaped value parsed out of it.
     *
     * <p>Exactly one of {@code value} / {@code failure} is non-null. A parse failure is returned
     * rather than thrown <b>because the completion still matters</b>: token counts, cost, latency and
     * cache buckets are all real and get recorded even when the body was unusable, so throwing would
     * discard the accounting for a call we already paid for.
     */
    public record Structured<T>(
            Completion completion,
            @Nullable T value,
            @Nullable StructuredOutputException failure) {

        public Structured {
            // Enforce the "exactly one" invariant the javadoc claims, so orElse can rely on it rather
            // than inventing a placeholder failure for a state that should be unreachable.
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException("structured result needs exactly one of value / failure");
            }
        }

        /** The parsed value, or {@code fallback} applied to the failure. The shape every caller wants. */
        public T orElse(Function<StructuredOutputException, T> fallback) {
            T parsed = value;
            return parsed != null ? parsed : fallback.apply(Objects.requireNonNull(failure));
        }
    }

    /**
     * Trace + cost + pace one chat call that must return a strictly-shaped answer, and hand back the
     * parsed bean.
     *
     * <p>This is the entry point every structured caller should use: it assembles the request in
     * whichever encoding {@code resolved.structuredMode()} calls for, so the caller declares its
     * {@link StructuredOutput.Spec} once and never branches on the provider. Everything else
     * (pacing, cost, cache accounting, the Langfuse span) is the shared {@link #call} path.
     *
     * <p>{@code cachePrefix} false skips the Bedrock cache breakpoint for a prompt whose shared
     * prefix will not be re-read — see {@code ChatModelFactory#cacheParamsFor} for the amortization.
     */
    public <T> Structured<T> callStructured(
            String spanName,
            ChatModelFactory.Resolved resolved,
            List<ChatMessage> messages,
            StructuredOutput.Spec<T> spec,
            boolean cachePrefix,
            @Nullable String projectId,
            Map<String, String> meta,
            @Nullable Context parentTrace) {
        ChatRequest request = structuredRequest(resolved, messages, spec, cachePrefix);
        Completion completion = call(spanName, resolved, request, projectId, meta, parentTrace);

        StructuredOutput.Mode mode = resolved.structuredMode();
        StructuredOutput.Payload payload = StructuredOutput.extract(completion.response(), mode);
        if (mode == StructuredOutput.Mode.TOOL_CALL && payload.source() != StructuredOutput.Source.TOOL_CALL) {
            structuredToolMissCounter.add(1);
            log.warn(
                    "structured output {} on {} came back as {} rather than a tool call; falling back to the message text",
                    spec.name(),
                    resolved.modelName(),
                    payload.source());
        }
        try {
            return new Structured<>(completion, StructuredOutput.parse(mapper, payload, spec), null);
        } catch (StructuredOutput.StructuredOutputException e) {
            return new Structured<>(completion, null, e);
        }
    }

    /**
     * Build the request in this model's structured-output encoding.
     *
     * <p>Bedrock carries the encoding in {@code parameters} because the cache point rides there too
     * and the request builder rejects a separate {@code responseFormat(...)} alongside
     * {@code parameters(...)}. Providers with no per-request parameters (OpenAI's automatic prefix
     * caching, Anthropic's build-time caching) carry a null {@code cacheParams} and take the encoding
     * on the request builder directly.
     */
    private static ChatRequest structuredRequest(
            ChatModelFactory.Resolved resolved,
            List<ChatMessage> messages,
            StructuredOutput.Spec<?> spec,
            boolean cachePrefix) {
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
        var cacheParams = resolved.cacheParams();
        if (cacheParams != null) {
            builder.parameters(cacheParams.apply(spec, cachePrefix));
        } else if (resolved.structuredMode() == StructuredOutput.Mode.TOOL_CALL) {
            builder.toolSpecifications(List.of(spec.toolSpecification()));
            builder.toolChoice(ToolChoice.REQUIRED);
        } else {
            builder.responseFormat(spec.responseFormat());
        }
        return builder.build();
    }

    // --- OpenTelemetry GenAI span enrichment ---------------------------------
    // The shape of gen_ai.input.messages / gen_ai.output.messages mirrors
    // TraceSpanMapper (the ingest-export path), so both boundaries agree on how
    // a message collapses to {role, parts:[{type:"text", content}]}.

    private static void recordRequest(
            Span span,
            ChatModelFactory.Resolved resolved,
            @Nullable String projectId,
            Map<String, String> meta,
            ChatRequest request) {
        String model = resolved.modelName();
        span.setAttribute("gen_ai.operation.name", "chat");
        span.setAttribute("gen_ai.system", TraceSpanMapper.inferSystem(model));
        if (model != null) span.setAttribute("gen_ai.request.model", model);
        // The tier we ASKED for, not one the response reports — Bedrock's Converse response carries no
        // served-tier field (see ChatModelFactory.Resolved). Mirrored into observation metadata so the
        // tier is filterable in Langfuse alongside the cost_details it scaled.
        ServiceTier tier = resolved.tier();
        if (tier != null) {
            span.setAttribute("gen_ai.request.service_tier", tier.wireName());
            span.setAttribute("langfuse.observation.metadata.service_tier", tier.wireName());
        }
        if (projectId != null) {
            span.setAttribute("tessary.project.id", projectId);
            span.setAttribute("langfuse.observation.metadata.project_id", projectId);
        }
        if (meta != null) {
            meta.forEach((k, v) -> {
                if (k != null && v != null) span.setAttribute("langfuse.observation.metadata." + k, v);
            });
        }

        ChatRequestParameters params = request.parameters();
        if (params != null) {
            if (params.temperature() != null) span.setAttribute("gen_ai.request.temperature", params.temperature());
            if (params.topP() != null) span.setAttribute("gen_ai.request.top_p", params.topP());
            if (params.topK() != null)
                span.setAttribute("gen_ai.request.top_k", params.topK().longValue());
            if (params.maxOutputTokens() != null)
                span.setAttribute(
                        "gen_ai.request.max_tokens", params.maxOutputTokens().longValue());
            if (params.frequencyPenalty() != null)
                span.setAttribute("gen_ai.request.frequency_penalty", params.frequencyPenalty());
            if (params.presencePenalty() != null)
                span.setAttribute("gen_ai.request.presence_penalty", params.presencePenalty());
            if (params.stopSequences() != null && !params.stopSequences().isEmpty()) {
                span.setAttribute(AttributeKey.stringArrayKey("gen_ai.request.stop_sequences"), params.stopSequences());
            }
        }

        span.setAttribute("gen_ai.input.messages", messagesToJson(request.messages()));
    }

    /** @return this call priced against the book in force, or null when the model/tier is unpriced. */
    private PlatformCallPricer.@Nullable PricedCall recordResponse(
            Span span, Completion completion, String modelName, @Nullable ServiceTier tier) {
        ChatResponse r = completion.response();
        span.setAttribute("tessary.latency_ms", completion.elapsedMs());
        if (r.modelName() != null) span.setAttribute("gen_ai.response.model", r.modelName());
        if (r.id() != null) span.setAttribute("gen_ai.response.id", r.id());
        if (r.finishReason() != null) {
            span.setAttribute(
                    AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                    List.of(r.finishReason().name().toLowerCase(Locale.ROOT)));
        }

        Integer in = r.tokenUsage() != null ? r.tokenUsage().inputTokenCount() : null;
        Integer out = r.tokenUsage() != null ? r.tokenUsage().outputTokenCount() : null;
        Integer total = r.tokenUsage() != null ? r.tokenUsage().totalTokenCount() : null;
        if (in != null) span.setAttribute("gen_ai.usage.input_tokens", in.longValue());
        if (out != null) span.setAttribute("gen_ai.usage.output_tokens", out.longValue());
        if (total != null) span.setAttribute("gen_ai.usage.total_tokens", total.longValue());

        CacheUsage cache = cacheUsage(r);
        // Langfuse reads usage_details for its token/cost breakdown, incl. cache.
        span.setAttribute("langfuse.observation.usage_details", usageDetailsJson(in, out, total, cache));

        // Cost: Langfuse can't price Bedrock model ids, so when the price book in force holds rates for
        // this model we compute the per-bucket USD cost ourselves and stamp cost_details (which Langfuse
        // uses verbatim). Models the book doesn't carry emit nothing → Langfuse prices them.
        //
        // PlatformCallPricer sums each bucket independently, which is only correct when the buckets are
        // non-overlapping. Bedrock/Anthropic report input_tokens excluding cache-read, but OpenAI's
        // prompt_tokens INCLUDES the cached tokens (cache.readOverlapsInput()). Pricing them as-is would
        // bill the cached tokens twice — once at the full input rate and again at the discounted
        // cache-read rate — so carve the cache-read tokens out of the input count first.
        // The tier scales every rate (Flex is 50% of Standard), so it must reach the pricer or a Flex
        // call is silently billed — and reported — at the Standard price.
        Integer billableInput = uncachedInput(in, cache);
        PlatformCallPricer.PricedCall priced = pricer == null
                ? null
                : pricer.price(modelName, tier, billableInput, out, cache.read(), cache.write())
                        .orElse(null);
        if (priced != null) {
            span.setAttribute("gen_ai.usage.cost", priced.total().doubleValue());
            span.setAttribute("langfuse.observation.cost_details", costDetailsJson(priced, cache));
        }

        span.setAttribute("gen_ai.output.messages", outputMessageToJson(r));
        return priced;
    }

    /**
     * Cache token counts a provider reported; shared with verdict assembly so cost,
     * usage_details and persistence all see the same numbers. Each provider returns its own
     * {@code TokenUsage} subtype — every cast is guarded by {@code instanceof} and the
     * accessors are nullable, so an unknown subtype (or a provider that reported no cache
     * activity) falls back to all-null.
     *
     * <ul>
     *   <li>Bedrock: {@code BedrockTokenUsage} — read + write (cache-creation) buckets; buckets
     *       are non-overlapping ({@code readOverlapsInput=false}).
     *   <li>Anthropic-direct: {@code AnthropicTokenUsage} — {@code cacheReadInputTokens} +
     *       {@code cacheCreationInputTokens} (populated when {@code cacheSystemMessages} is on);
     *       non-overlapping ({@code readOverlapsInput=false}).
     *   <li>OpenAI: {@code OpenAiTokenUsage} — {@code inputTokensDetails().cachedTokens()} is the
     *       read bucket; OpenAI's automatic prefix caching has no separate write charge, so
     *       write stays null. OpenAI's {@code prompt_tokens} <i>includes</i> the cached tokens,
     *       so {@code readOverlapsInput=true} and cost accounting carves them out of input.
     * </ul>
     */
    public static CacheUsage cacheUsage(ChatResponse response) {
        if (response.tokenUsage() instanceof BedrockTokenUsage bedrock) {
            return new CacheUsage(bedrock.cacheReadInputTokens(), bedrock.cacheWriteInputTokens(), false);
        }
        if (response.tokenUsage() instanceof AnthropicTokenUsage anthropic) {
            return new CacheUsage(anthropic.cacheReadInputTokens(), anthropic.cacheCreationInputTokens(), false);
        }
        if (response.tokenUsage() instanceof OpenAiTokenUsage openAi) {
            OpenAiTokenUsage.InputTokensDetails details = openAi.inputTokensDetails();
            Integer cachedRead = details != null ? details.cachedTokens() : null;
            return new CacheUsage(cachedRead, null, true);
        }
        return new CacheUsage(null, null, false);
    }

    /**
     * The input-token count to bill at the full input rate, with cache-read tokens carved out
     * when the provider folds them into its input bucket ({@link CacheUsage#readOverlapsInput()}).
     * Those carved-out tokens are billed separately at the discounted cache-read rate, so leaving
     * them in {@code input} would double-bill them. Non-overlapping providers (Bedrock/Anthropic)
     * return {@code in} unchanged. Clamped at 0 so a provider over-reporting cache-read can never
     * produce a negative billable input.
     */
    private static @Nullable Integer uncachedInput(@Nullable Integer in, CacheUsage cache) {
        Integer read = cache.read();
        if (in == null || !cache.readOverlapsInput() || read == null) return in;
        return Math.max(0, in - read);
    }

    /**
     * Book one completed call's token buckets and cost against its lane in the usage ledger.
     *
     * <p>The input bucket stored is the BILLABLE one ({@link #uncachedInput}), the same figure the cost
     * above was computed from — so a reader can add the four buckets up without double-counting the
     * cache-read tokens OpenAI folds into its input count, and so tokens and cost always tell the same
     * story. A null {@code costUsd} rides through as null: the model was unpriced, which the ledger
     * reports as a gap rather than as free, and it carries a null book version with it because no book
     * priced it.
     */
    private void bookUsage(ChatModelFactory.Resolved resolved, @Nullable String projectId, Completion completion) {
        LlmUsageAccountant ledger = accountant;
        if (ledger == null) return;
        ChatResponse r = completion.response();
        Integer in = r.tokenUsage() != null ? r.tokenUsage().inputTokenCount() : null;
        Integer out = r.tokenUsage() != null ? r.tokenUsage().outputTokenCount() : null;
        CacheUsage cache = cacheUsage(r);
        ledger.record(
                projectId,
                resolved.lane(),
                resolved.modelName(),
                resolved.tier(),
                resolved.platformFunded(),
                uncachedInput(in, cache),
                out,
                cache.read(),
                cache.write(),
                completion.costUsd(),
                completion.priceBookVersion(),
                (int) Math.min(Integer.MAX_VALUE, completion.elapsedMs()));
    }

    private static String messagesToJson(List<ChatMessage> messages) {
        ArrayNode arr = SPAN_MAPPER.createArrayNode();
        if (messages != null) {
            for (ChatMessage m : messages) {
                ObjectNode msg = arr.addObject();
                msg.put("role", roleOf(m));
                ObjectNode part = msg.putArray("parts").addObject();
                part.put("type", "text");
                part.put("content", messageText(m));
            }
        }
        return writeJson(arr);
    }

    /**
     * Serialize the assistant turn for the span.
     *
     * <p>Tool calls get their own part rather than being flattened into the text: under
     * {@link StructuredOutput.Mode#TOOL_CALL} the whole answer lives in the tool arguments and
     * {@code text()} is null, so emitting only text would leave every observation for such a model
     * showing a blank output in Langfuse — the calls would look successful and empty. A text part is
     * still emitted whenever there is text, so a response carrying both is fully represented.
     */
    private static String outputMessageToJson(ChatResponse r) {
        ArrayNode arr = SPAN_MAPPER.createArrayNode();
        ObjectNode msg = arr.addObject();
        msg.put("role", "assistant");
        ArrayNode parts = msg.putArray("parts");

        AiMessage ai = r.aiMessage();
        String text = ai != null && ai.text() != null ? ai.text() : "";
        List<ToolExecutionRequest> calls = ai == null ? List.of() : ai.toolExecutionRequests();
        if (calls == null) calls = List.of();

        // Keep emitting an (empty) text part when there is nothing else, so consumers that assume at
        // least one part are unaffected by this change.
        if (!text.isEmpty() || calls.isEmpty()) {
            ObjectNode part = parts.addObject();
            part.put("type", "text");
            part.put("content", text);
        }
        for (ToolExecutionRequest call : calls) {
            ObjectNode part = parts.addObject();
            part.put("type", "tool_call");
            part.put("name", call.name());
            part.put("content", call.arguments() == null ? "" : call.arguments());
        }
        if (r.finishReason() != null)
            msg.put("finish_reason", r.finishReason().name().toLowerCase(Locale.ROOT));
        return writeJson(arr);
    }

    private static String roleOf(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> "system";
            case AI -> "assistant";
            case TOOL_EXECUTION_RESULT -> "tool";
            default -> "user";
        };
    }

    private static String messageText(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> ((SystemMessage) m).text();
            case AI -> {
                AiMessage ai = (AiMessage) m;
                yield ai.text() == null ? "" : ai.text();
            }
            case TOOL_EXECUTION_RESULT -> ((ToolExecutionResultMessage) m).text();
            case USER -> userText((UserMessage) m);
            default -> "";
        };
    }

    /**
     * Flatten a user message to text. Non-text parts (images, audio, …) are noted
     * by type rather than inlined: shipping base64 media into a span attribute
     * would balloon it and risk OTLP size limits.
     */
    private static String userText(UserMessage u) {
        if (u.hasSingleText()) return u.singleText();
        StringBuilder sb = new StringBuilder();
        for (Content c : u.contents()) {
            String piece = c.type() == ContentType.TEXT
                    ? ((TextContent) c).text()
                    : "[" + c.type().name().toLowerCase(Locale.ROOT) + " content omitted]";
            if (piece != null && !piece.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(piece);
            }
        }
        return sb.toString();
    }

    private static String usageDetailsJson(
            @Nullable Integer in, @Nullable Integer out, @Nullable Integer total, CacheUsage cache) {
        ObjectNode n = SPAN_MAPPER.createObjectNode();
        // Emit the UNCACHED input so input + cache_read_input_tokens doesn't double-count cached
        // tokens in Langfuse's usage display for providers whose input bucket already includes
        // cache-read (OpenAI). Mirrors the cost carve-out; a no-op for Bedrock/Anthropic.
        Integer input = uncachedInput(in, cache);
        if (input != null) n.put("input", input);
        if (out != null) n.put("output", out);
        if (total != null) n.put("total", total);
        if (cache.read() != null) n.put("cache_read_input_tokens", cache.read());
        if (cache.write() != null) n.put("cache_creation_input_tokens", cache.write());
        return writeJson(n);
    }

    private static String costDetailsJson(PlatformCallPricer.PricedCall c, CacheUsage cache) {
        ObjectNode n = SPAN_MAPPER.createObjectNode();
        n.put("input", c.input());
        n.put("output", c.output());
        if (cache.read() != null) n.put("cache_read_input_tokens", c.cacheRead());
        if (cache.write() != null) n.put("cache_creation_input_tokens", c.cacheWrite());
        n.put("total", c.total());
        return writeJson(n);
    }

    private static String writeJson(JsonNode node) {
        try {
            return SPAN_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return node.isArray() ? "[]" : "{}";
        }
    }

    private Completion pacedCall(String label, ChatModelFactory.Resolved resolved, ChatRequest request) {
        boolean paced = resolved.paced();
        // Caching providers (Bedrock/Anthropic/OpenAI) are exactly the unpaced ones: they
        // rely on the warm prompt cache, so 429 back-off here risks outliving the cache TTL.
        // The rate-limited compat tiers don't cache, so the cache-expiry guard is moot there.
        boolean cacheRelevant = !paced;
        int maxRetries = pacer.getMaxRetries();
        // Cumulative back-off slept so far on this call. Compared against the cache TTL to flag
        // (and count) the likely cache expiry — a fan-out read that the wait turned into a re-write.
        long cumulativeBackoffMs = 0;
        boolean cacheExpiryFlagged = false;
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (paced) {
                try {
                    pacer.acquireSlot(pacer.estimateNextTokens());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TessaryException(JudgeError.UPSTREAM_FAILED, ie, "interrupted while pacing llm call");
                }
            }
            long start = System.currentTimeMillis();
            try {
                ChatResponse response = resolved.model().chat(request);
                long elapsed = System.currentTimeMillis() - start;
                if (paced) {
                    int used = 0;
                    if (response.tokenUsage() != null) {
                        Integer i = response.tokenUsage().inputTokenCount();
                        Integer o = response.tokenUsage().outputTokenCount();
                        used = (i == null ? 0 : i) + (o == null ? 0 : o);
                    }
                    pacer.recordCall(used);
                }
                return new Completion(response, elapsed);
            } catch (RuntimeException e) {
                if (LlmPacer.isRateLimit(e) && attempt < maxRetries) {
                    long backoff = pacer.computeBackoffMs(attempt, e);
                    cumulativeBackoffMs += backoff;
                    // On a caching provider, once the cumulative wait crosses the cache TTL the
                    // warm prefix has likely lapsed — log + count once so the re-write rate under
                    // induced 429s is observable. Bound the warning to one bump per call.
                    if (cacheRelevant && !cacheExpiryFlagged && cumulativeBackoffMs >= cacheTtlMs) {
                        cacheExpiryFlagged = true;
                        cacheLikelyExpiryCounter.add(1);
                        log.warn(
                                "rate-limited on {} cumulative back-off {}ms exceeded prompt-cache TTL {}ms — "
                                        + "warm cache likely expired, this grader's read may re-write the cache",
                                label,
                                cumulativeBackoffMs,
                                cacheTtlMs);
                    }
                    log.warn(
                            "rate-limited on {} attempt={}/{} backing off {}ms (cumulative {}ms)",
                            label,
                            attempt + 1,
                            maxRetries,
                            backoff,
                            cumulativeBackoffMs);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e; // NOPMD
                    }
                    last = e;
                    continue;
                }
                throw e;
            }
        }
        throw last != null ? last : new IllegalStateException("llm retries exhausted");
    }
}
