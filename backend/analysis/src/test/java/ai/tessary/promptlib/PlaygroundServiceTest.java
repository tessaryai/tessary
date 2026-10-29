// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.chat.ChatClient;
import ai.tessary.llm.chat.ChatDialect;
import ai.tessary.llm.chat.ChatMessage;
import ai.tessary.llm.chat.ChatProperties;
import ai.tessary.llm.chat.ChatProviderResolver;
import ai.tessary.llm.chat.ChatRequest;
import ai.tessary.llm.chat.ChatResult;
import ai.tessary.llm.chat.ChatTarget;
import ai.tessary.llm.chat.ChatUsage;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pricing.ModelRate;
import ai.tessary.pricing.ModelRates;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PlatformCallPricer;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.promptlib.PlaygroundDtos.Message;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunRequest;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunView;
import ai.tessary.promptlib.PlaygroundDtos.PromptRef;
import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.ResolvedVersion;
import ai.tessary.usage.LlmUsageAccountant;
import ai.tessary.usage.LlmUsageAccountant.Subject;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * One playground run over a fake chat client and the real price-book pricer. The bugs: a config key the playground
 * does not forward sent anyway or dropped silently, a value out of range reaching the provider, a cost booked from the
 * wrong source or a failed call booked at all, a sixth run let through past the bound, and a provider's quoted answer
 * leaking onto the span.
 */
@ExtendWith(MockitoExtension.class)
class PlaygroundServiceTest {

    private static final String ORG = "org-1";
    private static final String PID = "proj-1";
    private static final String MODEL = "gpt-5.5";
    private static final List<Message> MESSAGES =
            List.of(new Message("system", "Be terse."), new Message("user", "Say hi to Ada."));
    private static final List<ChatMessage> CHAT_MESSAGES =
            List.of(new ChatMessage("system", "Be terse."), new ChatMessage("user", "Say hi to Ada."));
    private static final ChatTarget OPENAI = new ChatTarget(
            ModelProvider.OPENAI,
            MODEL,
            URI.create("https://203.0.113.10/v1/chat/completions"),
            "k1",
            ChatDialect.OPENAI_CHAT_COMPLETIONS);
    private static final ChatTarget OPENROUTER = new ChatTarget(
            ModelProvider.OPENROUTER,
            "openai/" + MODEL,
            URI.create("https://openrouter.ai/api/v1/chat/completions"),
            "k1",
            ChatDialect.OPENAI_CHAT_COMPLETIONS);

    @Mock
    PromptService prompts;

    @Mock
    ChatProviderResolver targets;

    @Mock
    PriceBookRepository books;

    @Mock
    LlmUsageAccountant accountant;

    private final List<SpanData> exported = Collections.synchronizedList(new ArrayList<>());
    private final SdkTracerProvider tracing = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
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
            }))
            .build();
    private final OpenTelemetry otel = new OpenTelemetry() {
        @Override
        public TracerProvider getTracerProvider() {
            return tracing;
        }

        @Override
        public ContextPropagators getPropagators() {
            return ContextPropagators.noop();
        }
    };

    @AfterEach
    void close() {
        tracing.close();
    }

    private PlaygroundService service(ChatClient chat, int concurrency) {
        return new PlaygroundService(
                prompts,
                targets,
                chat,
                new PlatformCallPricer(new ModelResolver(books), books),
                accountant,
                otel,
                new ChatProperties(60_000, 2, concurrency, 4096));
    }

    private PlaygroundService service(ChatClient chat) {
        return service(chat, 4);
    }

    private static ChatClient answering(ChatUsage usage) {
        return (target, request) -> new ChatResult("Hi Ada.", "stop", target.modelId() + "-2026", usage, 1840);
    }

    private static PlaygroundRunRequest request(ModelProvider provider, String model, @Nullable PromptRef ref) {
        return new PlaygroundRunRequest(provider, model, MESSAGES, Map.of("temperature", 0.2), ref);
    }

    private static ErrorCode errorOf(Runnable call) {
        return assertThrows(TessaryException.class, call::run).error();
    }

    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    void toChatRequest_mapsTheFourKeysAndBothSpellings() {
        assertEquals(
                new ChatRequest(CHAT_MESSAGES, 0.2, 256, 0.9, List.of("END")),
                PlaygroundService.toChatRequest(
                        MESSAGES, config("temperature", 0.2, "maxTokens", 256, "topP", 0.9, "stop", "END"), 4096));
        assertEquals(
                new ChatRequest(CHAT_MESSAGES, 0.0, 256, 1.0, List.of("END", "STOP")),
                PlaygroundService.toChatRequest(
                        MESSAGES,
                        config("temperature", 0, "max_tokens", 256.0, "top_p", 1, "stop", List.of("END", "STOP")),
                        4096),
                "the snake_case spellings, an integral double and integer bounds all pass");
        assertEquals(
                new ChatRequest(CHAT_MESSAGES, null, null, null, List.of()),
                PlaygroundService.toChatRequest(MESSAGES, null, 4096),
                "no config sends nothing, so the provider's defaults apply");
        Map<String, Object> nulls = new HashMap<>();
        nulls.put("temperature", null);
        nulls.put("response_format", null);
        assertEquals(
                new ChatRequest(CHAT_MESSAGES, null, null, null, List.of()),
                PlaygroundService.toChatRequest(MESSAGES, nulls, 4096),
                "a JSON null is an absent key, even an unforwarded one");
    }

    @Test
    void anUnknownConfigKey_namesTheKeyToRemove() {
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> PlaygroundService.toChatRequest(
                        MESSAGES, config("temperature", 0.2, "response_format", Map.of("type", "json")), 4096));

        assertEquals(PlaygroundError.UNKNOWN_CONFIG_KEY, e.error());
        assertEquals(
                "Config key 'response_format' is not forwarded by the playground; remove it (forwarded: temperature,"
                        + " max_tokens, top_p, stop)",
                e.getMessage());
    }

    static Stream<Arguments> outOfRange() {
        return Stream.of(
                Arguments.of(config("temperature", 3), "config.temperature must be a number between 0 and 2"),
                Arguments.of(config("temperature", -0.1), "config.temperature must be a number between 0 and 2"),
                Arguments.of(config("temperature", "hot"), "config.temperature must be a number between 0 and 2"),
                Arguments.of(config("max_tokens", 5000), "config.max_tokens must be an integer between 1 and 4096"),
                Arguments.of(config("max_tokens", 0), "config.max_tokens must be an integer between 1 and 4096"),
                Arguments.of(config("max_tokens", 256.5), "config.max_tokens must be an integer between 1 and 4096"),
                Arguments.of(config("maxTokens", "256"), "config.max_tokens must be an integer between 1 and 4096"),
                Arguments.of(config("top_p", 0), "config.top_p must be a number above 0 and at most 1"),
                Arguments.of(config("top_p", 1.5), "config.top_p must be a number above 0 and at most 1"),
                Arguments.of(config("topP", true), "config.top_p must be a number above 0 and at most 1"),
                Arguments.of(config("stop", List.of(1)), "config.stop must be a string or an array of up to 4 strings"),
                Arguments.of(config("stop", List.of()), "config.stop must be a string or an array of up to 4 strings"),
                Arguments.of(
                        config("stop", List.of("a", "b", "c", "d", "e")),
                        "config.stop must be a string or an array of up to 4 strings"),
                Arguments.of(
                        config("stop", List.of("END", " ")),
                        "config.stop must be a string or an array of up to 4 strings"),
                Arguments.of(
                        config("stop", "x".repeat(65)), "config.stop must be a string or an array of up to 4 strings"),
                Arguments.of(config("stop", 7), "config.stop must be a string or an array of up to 4 strings"),
                Arguments.of(config("max_tokens", 256, "maxTokens", 256), "config has both max_tokens and maxTokens"),
                Arguments.of(config("top_p", 0.9, "topP", 0.9), "config has both top_p and topP"));
    }

    @ParameterizedTest
    @MethodSource("outOfRange")
    void aConfigValueOutOfRange_isAnInvalidParameter(Map<String, Object> config, String reason) {
        TessaryException e =
                assertThrows(TessaryException.class, () -> PlaygroundService.toChatRequest(MESSAGES, config, 4096));

        assertEquals(CommonError.INVALID_PARAMETER, e.error());
        assertEquals("Invalid parameter: " + reason, e.getMessage());
    }

    @Test
    void aBookPricedCall_booksTheBookTotalAndVersion() {
        when(targets.resolve(ORG, ModelProvider.OPENAI, MODEL)).thenReturn(OPENAI);
        when(books.hasModel(MODEL)).thenReturn(true);
        when(books.rateFor(MODEL))
                .thenReturn(Optional.of(new ModelRate(
                        "test-book",
                        new ModelRates(new BigDecimal("2.0"), new BigDecimal("8.0"), new BigDecimal("0.2"), null))));

        PlaygroundRunView view = service(answering(new ChatUsage(20, 8, 100, null, null)))
                .run(ORG, PID, request(ModelProvider.OPENAI, MODEL, null));

        BigDecimal cost = view.costUsd();
        assertEquals(0, new BigDecimal("0.000124").compareTo(cost), "20 x 2 + 8 x 8 + 100 x 0.2 per MTok");
        assertEquals(
                new PlaygroundRunView(
                        "Hi Ada.", "stop", 20, 8, 100, null, 1840, cost, "book", ModelProvider.OPENAI, MODEL),
                view);
        verify(accountant).recordChatCall(PID, "playground", MODEL, 20, 8, 100, null, cost, "test-book", 1840, null);
    }

    @Test
    void anOpenRouterCall_booksReportedCostAndNoBookVersion() {
        when(targets.resolve(ORG, ModelProvider.OPENROUTER, "openai/" + MODEL)).thenReturn(OPENROUTER);

        PlaygroundRunView view = service(answering(new ChatUsage(20, 8, null, null, new BigDecimal("0.00031"))))
                .run(ORG, PID, request(ModelProvider.OPENROUTER, "openai/" + MODEL, null));

        assertEquals(new BigDecimal("0.00031"), view.costUsd());
        assertEquals("openrouter", view.priceSource());
        verify(accountant)
                .recordChatCall(
                        PID,
                        "playground",
                        "openai/" + MODEL,
                        20,
                        8,
                        null,
                        null,
                        new BigDecimal("0.00031"),
                        null,
                        1840,
                        null);
    }

    @Test
    void anOpenRouterCallWithoutCost_isUnpriced() {
        when(targets.resolve(ORG, ModelProvider.OPENROUTER, "openai/" + MODEL)).thenReturn(OPENROUTER);

        PlaygroundRunView view = service(answering(new ChatUsage(20, 8, null, null, null)))
                .run(ORG, PID, request(ModelProvider.OPENROUTER, "openai/" + MODEL, null));

        assertNull(view.costUsd());
        assertEquals("unpriced", view.priceSource());
        verify(accountant)
                .recordChatCall(PID, "playground", "openai/" + MODEL, 20, 8, null, null, null, null, 1840, null);
    }

    @Test
    void anUnpricedModel_booksNullCost() {
        when(targets.resolve(ORG, ModelProvider.OPENAI, MODEL)).thenReturn(OPENAI);

        PlaygroundRunView view = service(answering(new ChatUsage(20, 8, null, null, null)))
                .run(ORG, PID, request(ModelProvider.OPENAI, MODEL, null));

        assertNull(view.costUsd());
        assertEquals("unpriced", view.priceSource());
        verify(accountant).recordChatCall(PID, "playground", MODEL, 20, 8, null, null, null, null, 1840, null);
    }

    @Test
    void aPromptRef_booksTheVersionIdAsSubject() {
        Instant now = Instant.parse("2026-10-18T09:12:44Z");
        PromptRow prompt = new PromptRow("prompt-1", PID, "greet", PromptType.TEXT, List.of(), 1, "u", now, now);
        PromptVersionRow v3 =
                new PromptVersionRow("pv-3", PID, "prompt-1", 3, "\"Hi\"", "{}", null, "u", CreateVia.UI, now);
        when(prompts.getVersion(PID, "prompt-1", 3))
                .thenReturn(
                        new ResolvedVersion(prompt, v3, new PromptBody.Text("Hi"), Map.of(), List.of("latest"), true));
        when(prompts.getVersion(PID, "prompt-1", 9))
                .thenThrow(new TessaryException(PromptsError.VERSION_NOT_FOUND, "greet", 9));
        when(targets.resolve(ORG, ModelProvider.OPENAI, MODEL)).thenReturn(OPENAI);
        AtomicInteger calls = new AtomicInteger();
        ChatClient counting = (target, req) -> {
            calls.incrementAndGet();
            return answering(new ChatUsage(20, 8, null, null, null)).complete(target, req);
        };
        PlaygroundService service = service(counting);

        service.run(ORG, PID, request(ModelProvider.OPENAI, MODEL, new PromptRef("prompt-1", 3)));
        verify(accountant)
                .recordChatCall(
                        PID,
                        "playground",
                        MODEL,
                        20,
                        8,
                        null,
                        null,
                        null,
                        null,
                        1840,
                        new Subject("prompt_version", "pv-3"));
        assertEquals(
                "pv-3",
                exported.get(0)
                        .getAttributes()
                        .get(AttributeKey.stringKey(
                                PlaygroundService.OBSERVATION_PREFIX + "metadata.prompt_version_id")));

        assertEquals(
                PromptsError.VERSION_NOT_FOUND,
                errorOf(() ->
                        service.run(ORG, PID, request(ModelProvider.OPENAI, MODEL, new PromptRef("prompt-1", 9)))));
        assertEquals(1, calls.get(), "an unknown version never reaches the provider");
    }

    @Test
    void aRun_emitsOneRootChatSpanWithNoMessageContent() {
        // Bug: a span without gen_ai.operation.name is dropped by the collector, and message text on it would ship a
        // customer's prompt to the platform's own telemetry.
        when(targets.resolve(ORG, ModelProvider.OPENAI, MODEL)).thenReturn(OPENAI);

        service(answering(new ChatUsage(20, 8, null, null, null)))
                .run(ORG, PID, request(ModelProvider.OPENAI, MODEL, null));

        assertEquals(1, exported.size());
        SpanData span = exported.get(0);
        assertEquals("playground-run", span.getName());
        assertEquals(SpanKind.CLIENT, span.getKind());
        assertEquals(
                Map.of(
                        "gen_ai.operation.name",
                        "chat",
                        "gen_ai.system",
                        "openai",
                        "gen_ai.request.model",
                        MODEL,
                        "tessary.project.id",
                        PID,
                        PlaygroundService.OBSERVATION_PREFIX + "metadata.project_id",
                        PID,
                        PlaygroundService.OBSERVATION_PREFIX + "metadata.lane",
                        "playground",
                        PlaygroundService.OBSERVATION_PREFIX + "metadata.provider",
                        "OPENAI",
                        "gen_ai.response.model",
                        MODEL + "-2026"),
                stringAttributes(span));
        assertEquals(20L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(8L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(1840L, span.getAttributes().get(AttributeKey.longKey("tessary.latency_ms")));
        assertNull(span.getAttributes().get(AttributeKey.doubleKey("gen_ai.usage.cost")), "unpriced has no cost");

        service(answering(new ChatUsage(null, null, null, null, null)))
                .run(ORG, PID, request(ModelProvider.OPENAI, MODEL, null));
        SpanData noUsage = exported.get(1);
        assertNull(
                noUsage.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")),
                "a count the provider did not report is absent, not 0");
        assertNull(noUsage.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    }

    @Test
    void aFifthConcurrentRun_isBusy() throws Exception {
        when(targets.resolve(ORG, ModelProvider.OPENAI, MODEL)).thenReturn(OPENAI);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatClient blockingFirst = (target, req) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS), "the test released the first run");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return answering(new ChatUsage(1, 1, null, null, null)).complete(target, req);
        };
        PlaygroundService service = service(blockingFirst, 1);
        PlaygroundRunRequest req = request(ModelProvider.OPENAI, MODEL, null);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PlaygroundRunView> first = pool.submit(() -> service.run(ORG, PID, req));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the first run reached the provider");

            TessaryException busy = assertThrows(TessaryException.class, () -> service.run(ORG, PID, req));
            assertEquals(PlaygroundError.BUSY, busy.error());
            assertEquals("The playground already has 1 runs in flight; try again in a moment", busy.getMessage());

            release.countDown();
            assertEquals("Hi Ada.", first.get(10, TimeUnit.SECONDS).content());
        }
        assertEquals("Hi Ada.", service.run(ORG, PID, req).content(), "the permit came back after the run");
        assertEquals(2, calls.get(), "the refused run never reached the provider");
    }

    @Test
    void aFailedCall_booksNothing() {
        when(targets.resolve(ORG, ModelProvider.OPENAI, MODEL)).thenReturn(OPENAI);
        ChatClient timingOut = (target, req) -> {
            throw new TessaryException(PlaygroundError.TIMEOUT, ModelProvider.OPENAI, 60_000);
        };

        assertEquals(
                PlaygroundError.TIMEOUT,
                errorOf(() -> service(timingOut).run(ORG, PID, request(ModelProvider.OPENAI, MODEL, null))));
        verify(accountant, never())
                .recordChatCall(
                        any(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void aFailedCall_putsTheCodeNotTheMessageOnTheSpan() {
        when(targets.resolve(ORG, ModelProvider.OPENAI, MODEL)).thenReturn(OPENAI);
        ChatClient refusing = (target, req) -> {
            throw new TessaryException(
                    PlaygroundError.REQUEST_REFUSED, ModelProvider.OPENAI, 400, "you said: Say hi to Ada.");
        };
        ChatClient crashing = (target, req) -> {
            throw new IllegalStateException("you said: Say hi to Ada.");
        };

        errorOf(() -> service(refusing).run(ORG, PID, request(ModelProvider.OPENAI, MODEL, null)));
        assertThrows(
                IllegalStateException.class,
                () -> service(crashing).run(ORG, PID, request(ModelProvider.OPENAI, MODEL, null)));

        assertEquals(List.of("REQUEST_REFUSED", "IllegalStateException"), statusMessages());
        for (SpanData span : exported) {
            assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
            assertEquals(
                    span.getAttributes()
                            .get(AttributeKey.stringKey(PlaygroundService.OBSERVATION_PREFIX + "status_message")),
                    span.getStatus().getDescription());
            assertEquals(
                    "ERROR",
                    span.getAttributes().get(AttributeKey.stringKey(PlaygroundService.OBSERVATION_PREFIX + "level")));
        }
        verify(accountant, never())
                .recordChatCall(
                        any(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void aConfigOrResolveFailure_opensNoSpan() {
        // Bug: a refused request emits an error span for a call that never happened.
        when(targets.resolve(eq(ORG), eq(ModelProvider.BEDROCK), anyString()))
                .thenThrow(new TessaryException(PlaygroundError.PROVIDER_UNSUPPORTED, ModelProvider.BEDROCK, "x"));
        PlaygroundService service = service(answering(new ChatUsage(null, null, null, null, null)));

        assertEquals(
                PlaygroundError.PROVIDER_UNSUPPORTED,
                errorOf(() -> service.run(ORG, PID, request(ModelProvider.BEDROCK, MODEL, null))));
        assertEquals(
                PlaygroundError.UNKNOWN_CONFIG_KEY,
                errorOf(() -> service.run(
                        ORG,
                        PID,
                        new PlaygroundRunRequest(ModelProvider.OPENAI, MODEL, MESSAGES, config("seed", 1), null))));
        assertEquals(List.of(), exported);
    }

    private List<String> statusMessages() {
        return exported.stream()
                .map(s -> s.getAttributes()
                        .get(AttributeKey.stringKey(PlaygroundService.OBSERVATION_PREFIX + "status_message")))
                .toList();
    }

    private static Map<String, String> stringAttributes(SpanData span) {
        Map<String, String> out = new HashMap<>();
        span.getAttributes().forEach((k, v) -> {
            if (v instanceof String s) out.put(k.getKey(), s);
        });
        return out;
    }
}
