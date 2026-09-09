// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.pricing.ModelRate;
import ai.tessary.pricing.ModelRates;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PlatformCallPricer;
import ai.tessary.pricing.PriceBookRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The shared LLM entry point every caller (judge, codegen, the agentic sandboxes) routes
 * through must emit a gen_ai span — so non-judge calls reach Langfuse too.
 */
class LlmCallerTest {

    @Test
    void emitsGenAiSpan_forANonRunCall_parentless() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter exporter = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                exported.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("generated code"))
                        .build());
        LlmPacer pacer = mock(LlmPacer.class);
        when(pacer.getMaxRetries()).thenReturn(0);

        LlmCaller llm = new LlmCaller(pacer, otel, new com.fasterxml.jackson.databind.ObjectMapper(), "5m");
        var resolved = new ChatModelFactory.Resolved(
                model,
                "anthropic.claude-sonnet-4-6",
                false,
                null,
                ServiceTier.STANDARD,
                true,
                StructuredOutput.Mode.NATIVE,
                ModelLane.RCA);
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("write a check"))
                .build();

        LlmCaller.Completion c = llm.call(
                "grader.codegen", resolved, request, "proj", Map.of("grader_id", "g1", "phase", "codegen"), null);

        assertEquals("generated code", c.response().aiMessage().text());
        SpanData span = exported.stream()
                .filter(s -> "grader.codegen".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no gen_ai span exported: " + exported));
        assertEquals("chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.input.messages")));
        assertEquals("g1", span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.grader_id")));
        assertEquals(
                "proj", span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.project_id")));
        // parentless (setNoParent) → its own trace, not nested under an ambient span.
        assertFalse(span.getParentSpanContext().isValid(), "non-run call must start its own trace");
    }

    // --- cacheUsage(): per-provider token-usage subtype extraction -------------
    // The static extractor is the single place cost, usage_details and verdict persistence
    // read cache tokens from, so it must handle every caching provider's TokenUsage subtype
    // and fall back to all-null for anything it doesn't recognise.

    @Test
    void cacheUsage_anthropicDirect_readsCacheReadAndCreationBuckets() {
        AnthropicTokenUsage usage = AnthropicTokenUsage.builder()
                .inputTokenCount(120)
                .outputTokenCount(40)
                .cacheReadInputTokens(800)
                .cacheCreationInputTokens(64)
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("{}"))
                .tokenUsage(usage)
                .build();

        LlmCaller.CacheUsage cache = LlmCaller.cacheUsage(response);

        assertEquals(800, cache.read());
        assertEquals(64, cache.write());
        // Anthropic reports non-overlapping buckets: input_tokens already excludes cache-read.
        assertFalse(cache.readOverlapsInput(), "Anthropic input_tokens excludes cache-read");
    }

    @Test
    void cacheUsage_openAi_readsCachedTokensAsReadAndLeavesWriteNull() {
        OpenAiTokenUsage usage = OpenAiTokenUsage.builder()
                .inputTokenCount(2048)
                .outputTokenCount(50)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(1024)
                        .build())
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("{}"))
                .tokenUsage(usage)
                .build();

        LlmCaller.CacheUsage cache = LlmCaller.cacheUsage(response);

        // OpenAI reports cached *reads* only; it has no separate cache-write charge.
        assertEquals(1024, cache.read());
        assertNull(cache.write());
        // OpenAI's prompt_tokens INCLUDES the cached tokens, so cost accounting must carve
        // them out of the input bucket to avoid double-billing.
        assertTrue(cache.readOverlapsInput(), "OpenAI prompt_tokens includes cached tokens");
    }

    @Test
    void cacheUsage_openAi_withNoDetails_fallsBackToNull() {
        OpenAiTokenUsage usage = OpenAiTokenUsage.builder()
                .inputTokenCount(10)
                .outputTokenCount(5)
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("{}"))
                .tokenUsage(usage)
                .build();

        LlmCaller.CacheUsage cache = LlmCaller.cacheUsage(response);

        assertNull(cache.read());
        assertNull(cache.write());
    }

    @Test
    void cacheUsage_unknownSubtype_fallsBackToAllNull() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("{}"))
                .tokenUsage(new TokenUsage(10, 5))
                .build();

        LlmCaller.CacheUsage cache = LlmCaller.cacheUsage(response);

        assertNull(cache.read());
        assertNull(cache.write());
    }

    // --- cache-aware retry/back-off ------------------------------------------
    // A 429 back-off during fan-out can outlive the prompt-cache TTL, silently turning a
    // cache read into a re-write. pacedCall tracks cumulative back-off against the configured
    // TTL and bumps tessary.cache.likely_expiry once when it crosses — tuning signal, no
    // behaviour change: the call must still complete after the retries.

    @Test
    void pacedCall_cumulativeBackoffPastTtl_countsLikelyCacheExpiry_andStillSucceeds() {
        InMemoryMetricReader metrics = new InMemoryMetricReader();
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setMeterProvider(
                        SdkMeterProvider.builder().registerMetricReader(metrics).build())
                .build();

        // A caching provider (unpaced) that 429s twice, then succeeds on the third attempt.
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("HTTP 429 too many requests"))
                .thenThrow(new RuntimeException("HTTP 429 too many requests"))
                .thenReturn(
                        ChatResponse.builder().aiMessage(AiMessage.from("{}")).build());
        LlmPacer pacer = mock(LlmPacer.class);
        when(pacer.getMaxRetries()).thenReturn(5);
        // 10ms back-off per attempt against a 1ms TTL → the first retry already crosses it.
        when(pacer.computeBackoffMs(org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(10L);

        // Tiny TTL so cumulative back-off crosses it immediately; provider is unpaced (caching).
        LlmCaller llm = new LlmCaller(pacer, otel, new com.fasterxml.jackson.databind.ObjectMapper(), "1ms");
        var resolved = new ChatModelFactory.Resolved(
                model,
                "anthropic.claude-sonnet-4-6",
                false,
                null,
                ServiceTier.STANDARD,
                true,
                StructuredOutput.Mode.NATIVE,
                ModelLane.RCA);
        ChatRequest request =
                ChatRequest.builder().messages(UserMessage.from("grade this")).build();

        LlmCaller.Completion c = llm.call("grader.verdict", resolved, request, "proj", Map.of(), null);

        // No functional regression: the call recovers and returns the success response.
        assertEquals("{}", c.response().aiMessage().text());
        // …and the likely-cache-expiry signal fired exactly once (bounded to one bump per call).
        assertEquals(1, metrics.likelyExpiryCount(), "cumulative back-off past TTL must flag cache expiry once");
    }

    @Test
    void pacedCall_backoffWithinTtl_doesNotCountExpiry() {
        InMemoryMetricReader metrics = new InMemoryMetricReader();
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setMeterProvider(
                        SdkMeterProvider.builder().registerMetricReader(metrics).build())
                .build();

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("HTTP 429 too many requests"))
                .thenReturn(
                        ChatResponse.builder().aiMessage(AiMessage.from("{}")).build());
        LlmPacer pacer = mock(LlmPacer.class);
        when(pacer.getMaxRetries()).thenReturn(5);
        when(pacer.computeBackoffMs(org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(1L);

        // 5m TTL, a single 1ms back-off → nowhere near expiry → counter stays at 0.
        LlmCaller llm = new LlmCaller(pacer, otel, new com.fasterxml.jackson.databind.ObjectMapper(), "5m");
        var resolved = new ChatModelFactory.Resolved(
                model,
                "anthropic.claude-sonnet-4-6",
                false,
                null,
                ServiceTier.STANDARD,
                true,
                StructuredOutput.Mode.NATIVE,
                ModelLane.RCA);
        ChatRequest request =
                ChatRequest.builder().messages(UserMessage.from("grade this")).build();

        llm.call("grader.verdict", resolved, request, "proj", Map.of(), null);

        assertEquals(0, metrics.likelyExpiryCount(), "back-off within TTL must not flag cache expiry");
    }

    @Test
    void parseCacheTtlMs_mapsSupportedAndUnknownValues() {
        assertEquals(300_000L, LlmCaller.parseCacheTtlMs("5m"), "5m → 300_000ms");
        assertEquals(3_600_000L, LlmCaller.parseCacheTtlMs("1h"), "1h → 3_600_000ms");
        assertEquals(30_000L, LlmCaller.parseCacheTtlMs("30s"));
        assertEquals(250L, LlmCaller.parseCacheTtlMs("250ms"));
        // Blank / unknown → 5m default so the guard always has a sane window.
        assertEquals(300_000L, LlmCaller.parseCacheTtlMs(null));
        assertEquals(300_000L, LlmCaller.parseCacheTtlMs("garbage"));
    }

    /** Minimal in-memory metric reader: sums the tessary.cache.likely_expiry counter on demand. */
    private static final class InMemoryMetricReader implements MetricReader {
        private final AtomicReference<CollectionRegistration> registration =
                new AtomicReference<>(CollectionRegistration.noop());

        @Override
        public void register(CollectionRegistration r) {
            registration.set(r);
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return AggregationTemporality.CUMULATIVE;
        }

        @Override
        public CompletableResultCode forceFlush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        long likelyExpiryCount() {
            long total = 0;
            for (MetricData md :
                    java.util.Objects.requireNonNull(registration.get()).collectAllMetrics()) {
                if ("tessary.cache.likely_expiry".equals(md.getName())) {
                    total += md.getLongSumData().getPoints().stream()
                            .mapToLong(p -> p.getValue())
                            .sum();
                }
            }
            return total;
        }
    }

    /** gpt-5.5 per MTok: input $5, output $30, cached input $0.50, and no cache-write bucket at all. */
    private static final ModelRates GPT55_RATES =
            new ModelRates(new BigDecimal("5.00"), new BigDecimal("30.00"), new BigDecimal("0.50"), null);

    /** anthropic.claude-sonnet-4-6 per MTok: the standard Anthropic layout, cache-read at 0.1x input. */
    private static final ModelRates SONNET_RATES = new ModelRates(
            new BigDecimal("3.00"), new BigDecimal("15.00"), new BigDecimal("0.30"), new BigDecimal("3.75"));

    // --- cache-aware cost accounting ----------------------------------------
    // OpenAI's prompt_tokens INCLUDES the cached tokens, so the cached tokens must be carved
    // out of the full-rate input bucket before pricing — otherwise they are billed twice
    // (full input rate + discounted cache-read rate), making cached tokens cost MORE.

    @Test
    void cost_openAi_carvesCachedTokensOutOfInput_soCacheSaves() throws Exception {
        // gpt-5.5: input $5/MTok, output $30/MTok, cached-input $0.50/MTok, no cache-write.
        // prompt_tokens=2048 INCLUDES cached=1024 → only 1024 tokens billed at full input.
        OpenAiTokenUsage usage = OpenAiTokenUsage.builder()
                .inputTokenCount(2048)
                .outputTokenCount(50)
                .totalTokenCount(2098)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(1024)
                        .build())
                .build();

        SpanData span = callAndCaptureSpan("gpt-5.5", GPT55_RATES, usage);
        JsonNode cost = new ObjectMapper()
                .readTree(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.cost_details")));

        // Only the 1024 uncached tokens are billed at full input ($5/MTok), not all 2048.
        assertEquals(
                5.00 * 1024 / 1_000_000, cost.get("input").asDouble(), 1e-12, "input billed on uncached tokens only");
        // The 1024 cached tokens are billed once, at the discounted cache-read rate.
        assertEquals(
                0.50 * 1024 / 1_000_000,
                cost.get("cache_read_input_tokens").asDouble(),
                1e-12,
                "cached tokens billed at discounted rate");
        assertEquals(30.00 * 50 / 1_000_000, cost.get("output").asDouble(), 1e-12);

        double total = cost.get("total").asDouble();
        // Caching must SAVE money: the total is strictly less than billing all 2048 input
        // tokens at the full rate (i.e. what an uncached call of the same size would cost).
        double uncachedEquivalent = 5.00 * 2048 / 1_000_000 + 30.00 * 50 / 1_000_000;
        assertTrue(total < uncachedEquivalent, "cached OpenAI call must cost less than the uncached equivalent");
        // And it equals the carved breakdown, NOT the double-billed sum.
        double doubleBilled = 5.00 * 2048 / 1_000_000 + 0.50 * 1024 / 1_000_000 + 30.00 * 50 / 1_000_000;
        assertTrue(total < doubleBilled, "cached tokens must not be billed at both full and cache-read rates");
    }

    @Test
    void cost_bedrock_keepsNonOverlappingBucketsUnchanged() throws Exception {
        // Bedrock reports input_tokens EXCLUDING cache-read, so the input bucket is billed
        // verbatim — no carve-out. anthropic.claude-sonnet-4-6: input $3, cache-read $0.30 /MTok.
        var bedrock = dev.langchain4j.model.bedrock.BedrockTokenUsage.builder()
                .inputTokenCount(1000)
                .outputTokenCount(20)
                .cacheReadInputTokens(800)
                .cacheWriteInputTokens(0)
                .build();

        SpanData span = callAndCaptureSpan("anthropic.claude-sonnet-4-6", SONNET_RATES, bedrock);
        JsonNode cost = new ObjectMapper()
                .readTree(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.cost_details")));

        // Full 1000 input tokens billed (not carved against the 800 cache-read tokens).
        assertEquals(3.00 * 1000 / 1_000_000, cost.get("input").asDouble(), 1e-12);
        assertEquals(0.30 * 800 / 1_000_000, cost.get("cache_read_input_tokens").asDouble(), 1e-12);
    }

    /**
     * Run one {@code LlmCaller.call} against a price book that carries {@code modelName} at {@code rates},
     * and return the exported gen_ai span.
     *
     * <p>The book is stubbed rather than loaded so the rate under test is stated in the test that depends
     * on it: what these cases pin is the bucket ARITHMETIC — the OpenAI carve-out, the Bedrock pass-through
     * — which must hold at any rate. The real book's contents are covered by the pricing tests in
     * {@code substrate}, and pinning a live rate here would only make a price refresh fail as a cache-math
     * regression.
     */
    private static SpanData callAndCaptureSpan(
            String modelName, ModelRates rates, dev.langchain4j.model.output.TokenUsage usage) {
        PriceBookRepository books = mock(PriceBookRepository.class);
        when(books.hasModel(modelName)).thenReturn(true);
        when(books.rateFor(modelName)).thenReturn(Optional.of(new ModelRate("test-book", rates)));
        PlatformCallPricer pricer = new PlatformCallPricer(new ModelResolver(books), books);
        List<SpanData> exported = new ArrayList<>();
        SpanExporter exporter = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                exported.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("{}"))
                        .tokenUsage(usage)
                        .build());
        LlmPacer pacer = mock(LlmPacer.class);
        when(pacer.getMaxRetries()).thenReturn(0);

        LlmCaller llm =
                new LlmCaller(pacer, otel, new com.fasterxml.jackson.databind.ObjectMapper(), "5m", null, pricer);
        var resolved = new ChatModelFactory.Resolved(
                model, modelName, false, null, ServiceTier.STANDARD, true, StructuredOutput.Mode.NATIVE, ModelLane.RCA);
        ChatRequest request =
                ChatRequest.builder().messages(UserMessage.from("grade this")).build();

        llm.call("grader.verdict", resolved, request, "proj", Map.of(), null);

        return exported.stream()
                .filter(s -> "grader.verdict".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no gen_ai span exported: " + exported));
    }
}
