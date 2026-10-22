// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.llm.ModelProvider;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.testsupport.LoopbackHttpStub;
import ai.tessary.testsupport.ScriptedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * One playground chat call per wire, against a mocked {@link HttpClient} and, for the two documented request shapes, a
 * loopback socket. Pins what our side decides: the path, the credential header and the body per wire, the parse of the
 * answer into ledger buckets, and the retry and refusal rules.
 */
@ExtendWith(MockitoExtension.class)
class HttpChatClientTest {

    private static final ChatProperties PROPS = new ChatProperties(20_000, 3, 4, 4096);

    private static final String OPENAI_ANSWER = "{\"model\":\"gpt-5.5-2026\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"Hi.\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Long> sleeps = new ArrayList<>();

    @Mock
    private HttpClient http;

    private HttpChatClient client() {
        return client(sleeps::add);
    }

    private HttpChatClient client(HttpChatClient.Sleeper sleeper) {
        return new HttpChatClient(http, mapper, sleeper, PROPS);
    }

    private static ChatTarget openAi() {
        return new ChatTarget(
                ModelProvider.OPENAI,
                "gpt-5.5",
                URI.create("https://api.openai.com/v1/chat/completions"),
                "k1",
                ChatDialect.OPENAI_CHAT_COMPLETIONS);
    }

    private static ChatTarget anthropic(String endpoint) {
        return new ChatTarget(
                ModelProvider.ANTHROPIC, "anthropic-model", URI.create(endpoint), "k1", ChatDialect.ANTHROPIC_MESSAGES);
    }

    private static ChatRequest request(List<ChatMessage> messages, @Nullable Integer maxTokens) {
        return new ChatRequest(messages, 0.2, maxTokens, 0.9, List.of("END"));
    }

    private static List<ChatMessage> systemAndUser() {
        return List.of(new ChatMessage("system", "You are terse."), new ChatMessage("user", "Summarize: hi"));
    }

    private static ChatRequest userOnly() {
        return new ChatRequest(List.of(new ChatMessage("user", "hi")), null, null, null, List.of());
    }

    @SuppressWarnings("unchecked")
    private void stub(HttpResponse<?>... responses) throws Exception {
        var stubbing = when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
        for (HttpResponse<?> r : responses) {
            stubbing = stubbing.thenReturn(r);
        }
    }

    @SuppressWarnings("unchecked")
    private void failWith(Exception... failures) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(failures);
    }

    @SuppressWarnings("unchecked")
    private List<HttpRequest> sent(int count) throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(count)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getAllValues();
    }

    private JsonNode sentBody() throws Exception {
        return mapper.readTree(ScriptedHttpClient.body(sent(1).get(0)));
    }

    private static HttpResponse<String> ok(String body) {
        return ScriptedHttpClient.response(200, body);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> retryAfter(int status, String seconds) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of(seconds)), (a, b) -> true));
        return response;
    }

    private static TessaryException failure(Runnable call) {
        return assertThrows(TessaryException.class, call::run);
    }

    @Test
    void openAiCall_postsTheDocumentedBodyWithABearerKey() throws Exception {
        try (LoopbackHttpStub stub = LoopbackHttpStub.answering(200, OPENAI_ANSWER)) {
            ChatTarget target = new ChatTarget(
                    ModelProvider.OPENAI,
                    "gpt-5.5",
                    URI.create(stub.baseUrl() + "/v1/chat/completions"),
                    "k1",
                    ChatDialect.OPENAI_CHAT_COMPLETIONS);

            ChatResult result = new HttpChatClient(mapper, PROPS).complete(target, request(systemAndUser(), 512));

            LoopbackHttpStub.Request sent = stub.lastRequest();
            assertEquals("POST", sent.line().split(" ", -1)[0]);
            assertEquals("/v1/chat/completions", sent.path());
            assertEquals("Bearer k1", sent.headers().get("authorization"));
            assertEquals("application/json", sent.headers().get("content-type"));
            assertEquals(
                    mapper.readTree("{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"system\",\"content\":\"You are"
                            + " terse.\"},{\"role\":\"user\",\"content\":\"Summarize: hi\"}],\"temperature\":0.2,"
                            + "\"max_completion_tokens\":512,\"top_p\":0.9,\"stop\":[\"END\"]}"),
                    mapper.readTree(sent.body()),
                    "no usage block: only OpenRouter reports a cost");
            assertEquals(
                    new ChatResult(
                            "Hi.", "stop", "gpt-5.5-2026", new ChatUsage(12, 3, null, null, null), result.latencyMs()),
                    result);
        }
    }

    /** Anthropic takes the system prompt beside the turns and a key header of its own, never a bearer token. */
    @Test
    void anthropicCall_usesXApiKeyAndHoistsSystem() throws Exception {
        String answer = "{\"model\":\"anthropic-model-1\",\"content\":[{\"type\":\"text\",\"text\":\"Hi.\"}],"
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":20,\"output_tokens\":8}}";
        try (LoopbackHttpStub stub = LoopbackHttpStub.answering(200, answer)) {
            ChatTarget target = anthropic(stub.baseUrl() + "/v1/messages");

            ChatResult result = new HttpChatClient(mapper, PROPS).complete(target, request(systemAndUser(), null));

            LoopbackHttpStub.Request sent = stub.lastRequest();
            assertEquals("/v1/messages", sent.path());
            assertEquals("k1", sent.headers().get("x-api-key"));
            assertEquals("2023-06-01", sent.headers().get("anthropic-version"));
            assertFalse(sent.headers().containsKey("authorization"), "the key must not ride a bearer header too");
            assertEquals(
                    mapper.readTree("{\"model\":\"anthropic-model\",\"max_tokens\":4096,\"system\":\"You are terse.\","
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"Summarize: hi\"}],\"temperature\":0.2,"
                            + "\"top_p\":0.9,\"stop_sequences\":[\"END\"]}"),
                    mapper.readTree(sent.body()),
                    "max_tokens is required by Anthropic, so the deployment ceiling fills it");
            assertEquals(
                    new ChatResult(
                            "Hi.",
                            "end_turn",
                            "anthropic-model-1",
                            new ChatUsage(20, 8, null, null, null),
                            result.latencyMs()),
                    result);
        }
    }

    /** Only OpenAI renamed the cap; every other OpenAI-compatible provider still reads max_tokens. */
    @Test
    void aMoonshotCall_usesMaxTokensNotMaxCompletionTokens() throws Exception {
        stub(ok(OPENAI_ANSWER));
        ChatTarget moonshot = new ChatTarget(
                ModelProvider.MOONSHOT,
                "moonshot-model",
                URI.create("https://api.moonshot.ai/v1/chat/completions"),
                "k1",
                ChatDialect.OPENAI_CHAT_COMPLETIONS);

        client().complete(moonshot, request(systemAndUser(), 512));

        JsonNode body = sentBody();
        assertEquals(512, body.path("max_tokens").intValue());
        assertTrue(body.path("max_completion_tokens").isMissingNode());
    }

    /** OpenRouter's cost is the bill; without the usage flag it is never reported and the run would be unpriced. */
    @Test
    void anOpenRouterTarget_asksForUsageAndReadsCost() throws Exception {
        stub(ok("{\"choices\":[{\"message\":{\"content\":\"Hi.\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"cost\":0.00031}}"));
        ChatTarget openRouter = new ChatTarget(
                ModelProvider.OPENROUTER,
                "vendor/model",
                URI.create("https://openrouter.ai/api/v1/chat/completions"),
                "k1",
                ChatDialect.OPENAI_CHAT_COMPLETIONS);

        ChatResult result = client().complete(openRouter, userOnly());

        assertEquals(mapper.readTree("{\"include\":true}"), sentBody().path("usage"));
        assertEquals(new BigDecimal("0.00031"), result.usage().reportedCostUsd());
        assertEquals("vendor/model", result.respondedModel(), "no echoed model falls back to the requested one");
    }

    /** OpenAI counts cached reads inside prompt_tokens; booking both would bill the cached part twice. */
    @Test
    void cachedPromptTokens_areCarvedOutOfInputTokens() throws Exception {
        stub(ok("{\"choices\":[{\"message\":{\"content\":\"Hi.\"}}],\"usage\":{\"prompt_tokens\":120,"
                + "\"completion_tokens\":8,\"prompt_tokens_details\":{\"cached_tokens\":100},\"cost\":\"0.1\"}}"));

        ChatResult result = client().complete(openAi(), userOnly());

        assertEquals(new ChatUsage(20, 8, 100, null, null), result.usage(), "a cost that is not a number is not read");
    }

    /** A present zero cache write stays zero: null means the provider did not say. */
    @Test
    void anthropicUsage_mapsCacheFieldsOneToOne() throws Exception {
        stub(ok("{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"},{\"type\":\"tool_use\",\"id\":\"t\"},"
                + "{\"type\":\"text\",\"text\":\" there\"}],\"usage\":{\"input_tokens\":20,\"output_tokens\":8,"
                + "\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":0}}"));

        ChatResult result = client().complete(anthropic("https://api.anthropic.com/v1/messages"), userOnly());

        assertEquals(
                new ChatResult(
                        "Hello there", null, "anthropic-model", new ChatUsage(20, 8, 100, 0, null), result.latencyMs()),
                result);
        JsonNode body = sentBody();
        assertTrue(body.path("system").isMissingNode(), "no system turn, no system field");
        assertTrue(body.path("stop_sequences").isMissingNode(), "an empty stop list is not sent");
        assertTrue(body.path("temperature").isMissingNode());
    }

    /** A filtered answer still cost tokens, so it is a result to book, not an error. */
    @Test
    void aContentFilteredAnswer_isAResultWithEmptyContent() throws Exception {
        stub(ok("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null},"
                + "\"finish_reason\":\"content_filter\"}]}"));

        ChatResult result = client().complete(openAi(), userOnly());

        assertEquals(
                new ChatResult(
                        "",
                        "content_filter",
                        "gpt-5.5",
                        new ChatUsage(null, null, null, null, null),
                        result.latencyMs()),
                result);
        JsonNode body = sentBody();
        assertTrue(body.path("temperature").isMissingNode(), "an unset setting is left to the provider");
        assertTrue(body.path("max_completion_tokens").isMissingNode());
        assertTrue(body.path("top_p").isMissingNode());
        assertTrue(body.path("stop").isMissingNode());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"id\":\"x\"}| no choices",
                "{\"choices\":[]}| no choices",
                "{\"error\":\"overloaded\"}| no choices",
                "{\"error\":{\"message\":\"x\"}}| x"
            })
    void anAnswerWithoutChoices_isMalformed(String body, String why) throws Exception {
        stub(ok(body));

        TessaryException e = failure(() -> client().complete(openAi(), userOnly()));

        assertSame(PlaygroundError.MALFORMED_RESPONSE, e.error());
        assertEquals(PlaygroundError.MALFORMED_RESPONSE.render(ModelProvider.OPENAI, why), e.getMessage());
    }

    @Test
    void anAnthropicAnswerWithoutContent_isMalformed() throws Exception {
        stub(ok("{\"content\":\"Hi.\"}"));

        TessaryException e =
                failure(() -> client().complete(anthropic("https://api.anthropic.com/v1/messages"), userOnly()));

        assertEquals(PlaygroundError.MALFORMED_RESPONSE.render(ModelProvider.ANTHROPIC, "no content"), e.getMessage());
    }

    /** A parse error would echo the body, which can quote the prompt; the message names only what was wrong. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {"[]| body is not an object", "not json| body is not JSON"})
    void aBodyThatIsNotAJsonObject_isMalformed(String body, String why) throws Exception {
        stub(ok(body));

        TessaryException e = failure(() -> client().complete(openAi(), userOnly()));

        assertEquals(PlaygroundError.MALFORMED_RESPONSE.render(ModelProvider.OPENAI, why), e.getMessage());
        assertNull(e.getCause(), "no parser exception attached: its message quotes the body");
    }

    @Test
    void a429ThenA200_retriesHonouringRetryAfter() throws Exception {
        stub(retryAfter(429, "3"), ok(OPENAI_ANSWER));

        ChatResult result = client().complete(openAi(), userOnly());

        assertEquals("Hi.", result.content());
        assertEquals(List.of(3_000L), sleeps);
        sent(2);
    }

    @Test
    void threeServerErrors_failAsUnavailableAfterBackingOff() throws Exception {
        stub(
                ScriptedHttpClient.response(503, ""),
                ScriptedHttpClient.response(502, ""),
                ScriptedHttpClient.response(500, ""));

        TessaryException e = failure(() -> client().complete(openAi(), userOnly()));

        assertSame(PlaygroundError.PROVIDER_UNAVAILABLE, e.error());
        assertEquals(PlaygroundError.PROVIDER_UNAVAILABLE.render(ModelProvider.OPENAI, "HTTP 500"), e.getMessage());
        assertEquals(2, sleeps.size(), "backs off between the three attempts, not after the last");
        assertTrue(sleeps.get(0) >= 1_000L && sleeps.get(0) <= 1_250L, "first back-off is 1s plus jitter");
        assertTrue(sleeps.get(1) >= 2_000L && sleeps.get(1) <= 2_500L, "second back-off is 2s plus jitter");
        sent(3);
    }

    @Test
    void aRetryAfterBeyondTheCeiling_failsAtOnce() throws Exception {
        stub(retryAfter(429, "60"));

        TessaryException e = failure(() -> client().complete(openAi(), userOnly()));

        assertSame(PlaygroundError.PROVIDER_UNAVAILABLE, e.error());
        assertEquals(List.of(), sleeps, "a provider asking for a minute is down for this run");
        sent(1);
    }

    /** A person is waiting at a button: a second full timeout would double the wait for the same answer. */
    @Test
    void aTimeout_isNotRetried() throws Exception {
        failWith(new HttpTimeoutException("request timed out"));

        TessaryException e = failure(() -> client().complete(openAi(), userOnly()));

        assertSame(PlaygroundError.TIMEOUT, e.error());
        assertEquals(PlaygroundError.TIMEOUT.render(ModelProvider.OPENAI, 20_000L), e.getMessage());
        assertEquals(List.of(), sleeps);
        sent(1);
    }

    @Test
    void aResetConnection_retriesThenFailsAsUnavailable() throws Exception {
        failWith(new IOException("reset"), new IOException("reset"), new IOException("reset"));

        TessaryException e = failure(() -> client().complete(openAi(), userOnly()));

        assertSame(PlaygroundError.PROVIDER_UNAVAILABLE, e.error());
        assertEquals(PlaygroundError.PROVIDER_UNAVAILABLE.render(ModelProvider.OPENAI, "IOException"), e.getMessage());
        assertEquals(2, sleeps.size());
        sent(3);
    }

    static Stream<Arguments> statusesARetryCannotFix() {
        return Stream.of(
                Arguments.of(401, PlaygroundError.PROVIDER_REJECTED, "Provider OPENAI rejected the key (HTTP 401)"),
                Arguments.of(403, PlaygroundError.PROVIDER_REJECTED, "Provider OPENAI rejected the key (HTTP 403)"),
                Arguments.of(
                        402,
                        PlaygroundError.PROVIDER_NO_CREDIT,
                        "Provider OPENAI has no credit left on the key (HTTP 402)"),
                // The provider's own words ride the message: they name the setting to change.
                Arguments.of(
                        400,
                        PlaygroundError.REQUEST_REFUSED,
                        "Provider OPENAI refused the request (HTTP 400): Unsupported value: temperature"));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("statusesARetryCannotFix")
    void statusesARetryCannotFix_failWithoutRetrying(int status, PlaygroundError expected, String message)
            throws Exception {
        stub(ScriptedHttpClient.response(
                status, "{\"error\":{\"message\":\"Unsupported value: temperature\",\"code\":\"x\"}}"));

        TessaryException e = failure(() -> client().complete(openAi(), userOnly()));

        assertSame(expected, e.error());
        assertEquals(message, e.getMessage());
        assertEquals(List.of(), sleeps);
        sent(1);
    }

    /** A refusal's detail: the JSON error message, else the raw body, whitespace folded and capped. */
    @Test
    void refusal_takesTheProvidersMessageOrTheBodyAndCapsIt() {
        assertEquals("no detail", HttpChatClient.refusal(mapper, null));
        assertEquals("no detail", HttpChatClient.refusal(mapper, "  "));
        assertEquals(
                "Model x does not exist",
                HttpChatClient.refusal(mapper, "{\"error\":{\"message\":\"Model x does not exist\"}}"));
        assertEquals("bad request", HttpChatClient.refusal(mapper, "{\"message\":\"bad request\"}"));
        assertEquals(
                "{\"error\":{\"message\":\" \"}}", HttpChatClient.refusal(mapper, "{\"error\":{\"message\":\" \"}}"));
        assertEquals("{\"error\":{\"code\":400}}", HttpChatClient.refusal(mapper, "{\"error\":{\"code\":400}}"));
        assertEquals("[1]", HttpChatClient.refusal(mapper, "[1]"));
        assertEquals("not json at all", HttpChatClient.refusal(mapper, "  not\njson   at all "));
        String capped = HttpChatClient.refusal(mapper, "x".repeat(HttpChatClient.REFUSAL_CHARS + 50));
        assertEquals("x".repeat(HttpChatClient.REFUSAL_CHARS) + "…", capped);
    }

    @Test
    void anInterruptedSend_failsAsUnavailableAndKeepsTheInterrupt() throws Exception {
        failWith(new InterruptedException());

        TessaryException e;
        boolean interrupted;
        try {
            e = failure(() -> client().complete(openAi(), userOnly()));
        } finally {
            interrupted = Thread.interrupted();
        }

        assertSame(PlaygroundError.PROVIDER_UNAVAILABLE, e.error());
        assertTrue(interrupted, "the request thread's interrupt must survive the failed call");
    }

    @Test
    void anInterruptedBackOff_failsAsUnavailableAndKeepsTheInterrupt() throws Exception {
        stub(ScriptedHttpClient.response(503, ""));
        HttpChatClient interruptedWhileWaiting = client(ms -> {
            throw new InterruptedException();
        });

        TessaryException e;
        boolean interrupted;
        try {
            e = failure(() -> interruptedWhileWaiting.complete(openAi(), userOnly()));
        } finally {
            interrupted = Thread.interrupted();
        }

        assertEquals(PlaygroundError.PROVIDER_UNAVAILABLE.render(ModelProvider.OPENAI, "interrupted"), e.getMessage());
        assertTrue(interrupted, "the request thread's interrupt must survive the back-off");
        sent(1);
    }

    @Test
    void targetToString_neverPrintsTheKey() {
        assertFalse(openAi().toString().contains("k1"));
    }
}
