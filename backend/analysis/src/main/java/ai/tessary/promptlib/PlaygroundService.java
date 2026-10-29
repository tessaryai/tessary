// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.llm.ModelCatalog;
import ai.tessary.llm.chat.ChatClient;
import ai.tessary.llm.chat.ChatMessage;
import ai.tessary.llm.chat.ChatProperties;
import ai.tessary.llm.chat.ChatProviderResolver;
import ai.tessary.llm.chat.ChatRequest;
import ai.tessary.llm.chat.ChatResult;
import ai.tessary.llm.chat.ChatTarget;
import ai.tessary.llm.chat.ChatUsage;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.pricing.PlatformCallPricer;
import ai.tessary.promptlib.PlaygroundDtos.Message;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunRequest;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunView;
import ai.tessary.promptlib.PlaygroundDtos.PromptRef;
import ai.tessary.sandbox.AgentSpanTelemetry;
import ai.tessary.usage.LlmUsageAccountant;
import ai.tessary.usage.LlmUsageAccountant.Subject;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One person-started chat call on the org's own provider key, priced and booked in {@code llm_call} under lane
 * {@code playground}. Only {@code temperature}, {@code max_tokens}, {@code top_p} and {@code stop} are forwarded; any
 * other config key is refused by name rather than dropped. A failed call books nothing. A run is never ingested into
 * the project's traces.
 */
@Service
public class PlaygroundService {

    private static final Logger log = LoggerFactory.getLogger(PlaygroundService.class);

    static final String LANE = "playground";
    static final String SPAN_NAME = "playground-run";

    /** The observation attribute prefix the self-tracing backend reads, the same keys JevDecisionClient stamps. */
    static final String OBSERVATION_PREFIX = "lang" + "fuse.observation.";

    private static final Set<String> FORWARDED =
            Set.of("temperature", "max_tokens", "maxTokens", "top_p", "topP", "stop");
    private static final int MAX_STOP_SEQUENCES = 4;
    private static final int MAX_STOP_CHARS = 64;

    private final PromptService prompts;
    private final ChatProviderResolver targets;
    private final ChatClient chat;
    private final PlatformCallPricer pricer;
    private final LlmUsageAccountant accountant;
    private final Tracer tracer;
    private final int maxOutputTokens;
    private final int concurrency;
    private final Semaphore permits;

    public PlaygroundService(
            PromptService prompts,
            ChatProviderResolver targets,
            ChatClient chat,
            PlatformCallPricer pricer,
            LlmUsageAccountant accountant,
            OpenTelemetry openTelemetry,
            ChatProperties props) {
        this.prompts = prompts;
        this.targets = targets;
        this.chat = chat;
        this.pricer = pricer;
        this.accountant = accountant;
        this.tracer = openTelemetry.getTracer("ai.tessary.llm");
        this.maxOutputTokens = props.maxOutputTokens();
        this.concurrency = Math.max(1, props.concurrency());
        this.permits = new Semaphore(concurrency);
    }

    private record Priced(
            @Nullable BigDecimal costUsd,
            String source,
            @Nullable String bookVersion) {}

    public PlaygroundRunView run(String orgId, String projectId, PlaygroundRunRequest req) {
        ChatRequest request = toChatRequest(req.messages(), req.config(), maxOutputTokens);
        PromptRef ref = req.promptRef();
        Subject subject = ref == null
                ? null
                : new Subject(
                        "prompt_version",
                        prompts.getVersion(projectId, ref.promptId(), ref.version())
                                .version()
                                .id());
        ChatTarget target = targets.resolve(orgId, req.provider(), req.model());

        if (!permits.tryAcquire()) throw new TessaryException(PlaygroundError.BUSY, concurrency);
        Span span = startSpan(projectId, target, subject);
        try (var _ = span.makeCurrent()) {
            ChatResult result;
            try {
                result = chat.complete(target, request);
            } finally {
                permits.release();
            }
            ChatUsage usage = result.usage();
            Priced priced = price(target, usage);
            recordResponse(span, result, priced);
            accountant.recordChatCall(
                    projectId,
                    LANE,
                    target.modelId(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.cacheReadTokens(),
                    usage.cacheWriteTokens(),
                    priced.costUsd(),
                    priced.bookVersion(),
                    (int) Math.min(Integer.MAX_VALUE, result.latencyMs()),
                    subject);
            logCompleted(projectId, target, result, priced, subject);
            return new PlaygroundRunView(
                    result.content(),
                    result.finishReason(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.cacheReadTokens(),
                    usage.cacheWriteTokens(),
                    result.latencyMs(),
                    priced.costUsd(),
                    priced.source(),
                    target.provider(),
                    target.modelId());
        } catch (RuntimeException e) {
            // The code, never the message: a refusal's message quotes the provider's answer.
            String code = e instanceof TessaryException t
                    ? t.error().name()
                    : e.getClass().getSimpleName();
            AgentSpanTelemetry.markError(span, code);
            throw e;
        } finally {
            span.end();
        }
    }

    /** An OpenRouter bill is OpenRouter's reported cost; anything else prices from the book or stays unpriced. */
    private Priced price(ChatTarget target, ChatUsage usage) {
        if (target.billedByOpenRouter()) {
            BigDecimal reported = usage.reportedCostUsd();
            return new Priced(reported, reported == null ? "unpriced" : "openrouter", null);
        }
        return pricer.price(
                        ModelCatalog.pricingId(target.provider(), target.modelId()),
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cacheReadTokens(),
                        usage.cacheWriteTokens())
                .map(p -> new Priced(p.total(), "book", p.priceBookVersion()))
                .orElseGet(() -> new Priced(null, "unpriced", null));
    }

    private Span startSpan(String projectId, ChatTarget target, @Nullable Subject subject) {
        SpanBuilder builder = tracer.spanBuilder(SPAN_NAME)
                .setSpanKind(SpanKind.CLIENT)
                .setNoParent()
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.system", target.provider().name().toLowerCase(Locale.ROOT))
                .setAttribute("gen_ai.request.model", target.modelId())
                .setAttribute("tessary.project.id", projectId)
                .setAttribute(OBSERVATION_PREFIX + "metadata.project_id", projectId)
                .setAttribute(OBSERVATION_PREFIX + "metadata.lane", LANE)
                .setAttribute(
                        OBSERVATION_PREFIX + "metadata.provider",
                        target.provider().name());
        if (subject != null) builder.setAttribute(OBSERVATION_PREFIX + "metadata.prompt_version_id", subject.id());
        return builder.startSpan();
    }

    private static void recordResponse(Span span, ChatResult result, Priced priced) {
        span.setAttribute("gen_ai.response.model", result.respondedModel());
        span.setAttribute("tessary.latency_ms", result.latencyMs());
        Integer in = result.usage().inputTokens();
        if (in != null) span.setAttribute("gen_ai.usage.input_tokens", in.longValue());
        Integer out = result.usage().outputTokens();
        if (out != null) span.setAttribute("gen_ai.usage.output_tokens", out.longValue());
        BigDecimal cost = priced.costUsd();
        if (cost != null) span.setAttribute("gen_ai.usage.cost", cost.doubleValue());
    }

    private static void logCompleted(
            String projectId, ChatTarget target, ChatResult result, Priced priced, @Nullable Subject subject) {
        BigDecimal cost = priced.costUsd();
        StructuredLog.info(log, Markers.OPS, "playground.run.completed")
                .message(
                        "playground run completed in %dms on %s %s: %s in, %s out, %s",
                        result.latencyMs(),
                        target.provider(),
                        target.modelId(),
                        String.valueOf(result.usage().inputTokens()),
                        String.valueOf(result.usage().outputTokens()),
                        cost == null ? "unpriced" : "$" + cost.toPlainString() + " (" + priced.source() + ")")
                .field("projectId", projectId)
                .field("provider", target.provider().name())
                .field("model", target.modelId())
                .field("durationMs", result.latencyMs())
                .field("inputTokens", result.usage().inputTokens())
                .field("outputTokens", result.usage().outputTokens())
                .field("costUsd", cost)
                .field("priceSource", priced.source())
                .field("promptVersionId", subject == null ? null : subject.id())
                .log();
    }

    /**
     * Maps a stored prompt config onto the four forwarded settings. A JSON {@code null} is an absent key. Both
     * spellings of one setting at once is refused, since either choice would silently drop the other.
     */
    static ChatRequest toChatRequest(List<Message> messages, @Nullable Map<String, Object> config, int max) {
        List<ChatMessage> chatMessages = messages.stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
        if (config == null) return new ChatRequest(chatMessages, null, null, null, List.of());
        for (Map.Entry<String, Object> e : config.entrySet()) {
            if (e.getValue() != null && !FORWARDED.contains(e.getKey())) {
                throw new TessaryException(PlaygroundError.UNKNOWN_CONFIG_KEY, e.getKey());
            }
        }
        Object temperature = config.get("temperature");
        Object maxTokens = oneOf(config, "max_tokens", "maxTokens");
        Object topP = oneOf(config, "top_p", "topP");
        Object stop = config.get("stop");
        return new ChatRequest(
                chatMessages,
                temperature == null ? null : temperature(temperature),
                maxTokens == null ? null : maxTokens(maxTokens, max),
                topP == null ? null : topP(topP),
                stop == null ? List.of() : stop(stop));
    }

    private static @Nullable Object oneOf(Map<String, Object> config, String snake, String camel) {
        Object a = config.get(snake);
        Object b = config.get(camel);
        if (a != null && b != null) {
            throw new TessaryException(CommonError.INVALID_PARAMETER, "config has both " + snake + " and " + camel);
        }
        return a != null ? a : b;
    }

    private static double temperature(Object value) {
        if (value instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 2) return n.doubleValue();
        throw invalid("config.temperature must be a number between 0 and 2");
    }

    /** Through {@link BigDecimal} so 256.0 passes and 256.5 fails without comparing doubles for equality. */
    private static int maxTokens(Object value, int max) {
        if (value instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString());
            if (d.stripTrailingZeros().scale() <= 0
                    && d.compareTo(BigDecimal.ONE) >= 0
                    && d.compareTo(BigDecimal.valueOf(max)) <= 0) {
                return d.intValue();
            }
        }
        throw invalid("config.max_tokens must be an integer between 1 and " + max);
    }

    private static double topP(Object value) {
        if (value instanceof Number n && n.doubleValue() > 0 && n.doubleValue() <= 1) return n.doubleValue();
        throw invalid("config.top_p must be a number above 0 and at most 1");
    }

    private static List<String> stop(Object value) {
        List<?> items = value instanceof List<?> list ? list : List.of(value);
        if (items.isEmpty() || items.size() > MAX_STOP_SEQUENCES) throw invalidStop();
        for (Object item : items) {
            if (!(item instanceof String s) || s.isBlank() || s.length() > MAX_STOP_CHARS) throw invalidStop();
        }
        return items.stream().map(String.class::cast).toList();
    }

    private static TessaryException invalidStop() {
        return invalid("config.stop must be a string or an array of up to 4 strings");
    }

    private static TessaryException invalid(String reason) {
        return new TessaryException(CommonError.INVALID_PARAMETER, reason);
    }
}
