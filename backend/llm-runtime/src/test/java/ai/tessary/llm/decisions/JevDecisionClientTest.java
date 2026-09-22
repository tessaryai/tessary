// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.llm.ModelProvider;
import ai.tessary.open.errors.DecisionError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pricing.ModelRate;
import ai.tessary.pricing.ModelRates;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PlatformCallPricer;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Against a mocked {@link HttpClient}, as the catalog listers are ({@code com.sun.net.httpserver} is
 * forbidden here). Pins what our side decides: the endpoint, key and body per gateway, the parse of
 * the documented envelope, the retry and refusal rules, and the pricing id on both routes.
 */
class JevDecisionClientTest {

    private static final String BOOK = "test-book";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);
    private final LlmUsageAccountant accountant = mock(LlmUsageAccountant.class);
    private final List<Long> sleeps = new ArrayList<>();

    private JevDecisionClient client() {
        PriceBookRepository books = mock(PriceBookRepository.class);
        when(books.hasModel("typesafe/jev-latest")).thenReturn(true);
        when(books.rateFor("typesafe/jev-latest"))
                .thenReturn(
                        Optional.of(new ModelRate(BOOK, new ModelRates(new BigDecimal("0.042"), null, null, null))));
        PlatformCallPricer pricer = new PlatformCallPricer(new ModelResolver(books), books);
        return new JevDecisionClient(
                http, mapper, OpenTelemetry.noop(), pricer, accountant, sleeps::add, Duration.ofSeconds(20), 3);
    }

    private static DecisionTarget typesafe() {
        return new DecisionTarget(
                ModelProvider.TYPESAFE,
                "jev-latest",
                DecisionTarget.endpointFor(ModelProvider.TYPESAFE, null),
                "ts-key");
    }

    private static DecisionTarget openrouter() {
        return new DecisionTarget(
                ModelProvider.OPENROUTER,
                "typesafe/jev-latest",
                DecisionTarget.endpointFor(ModelProvider.OPENROUTER, null),
                "or-key");
    }

    private DecisionRequest request() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("unhappy_with_assistant", "Unhappy because of the assistant.");
        criteria.put("unhappy_other_cause", "Unhappy about something else.");
        criteria.put("neutral_or_positive", "Neutral or positive.");
        ObjectNode state = mapper.createObjectNode().put("current_user_message", "that is wrong again");
        return new DecisionRequest(
                state, Map.of("user_stance", DecisionRequest.Question.choice("Which fits?", criteria)));
    }

    private static String answer(String usageExtra) {
        return "{\"model\":\"jev-1.13-20260917\",\"answers\":{\"user_stance\":"
                + "{\"type\":\"choice\",\"choice\":\"unhappy_with_assistant\",\"confidence\":0.7,"
                + "\"probabilities\":{\"unhappy_with_assistant\":0.71,\"unhappy_other_cause\":0.04,"
                + "\"neutral_or_positive\":0.25}}},\"usage\":{\"input_tokens\":1000,\"output_tokens\":12" + usageExtra
                + "}}";
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
        return response;
    }

    private static HttpResponse<String> response(int status, String body) {
        return response(status, body, Map.of());
    }

    @SuppressWarnings("unchecked")
    private void stub(HttpResponse<String>... responses) throws Exception {
        var stubbing = when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
        for (HttpResponse<String> r : responses) {
            stubbing = stubbing.thenReturn(r);
        }
    }

    @SuppressWarnings("unchecked")
    private List<HttpRequest> sent(int count) throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(count)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getAllValues();
    }

    @Test
    void typesafeCall_postsToSystemOneWithBearerKeyAndParsesTheEnvelope() throws Exception {
        stub(response(200, answer("")));

        DecisionAnswer answer = client().decide("p1", "frustration", typesafe(), request());

        HttpRequest sent = sent(1).get(0);
        assertEquals(URI.create("https://api.typesafe.ai/v1/systemone"), sent.uri());
        assertEquals(List.of("Bearer ts-key"), sent.headers().allValues("Authorization"));
        assertEquals("POST", sent.method());
        assertEquals("jev-latest", answer.requestedModel());
        assertEquals("jev-1.13-20260917", answer.respondedModel());
        assertEquals(
                "unhappy_with_assistant", answer.answers().get("user_stance").choice());
        assertEquals(0.71, answer.answers().get("user_stance").probability("unhappy_with_assistant"));
        assertEquals(1000, answer.inputTokens());
        assertEquals(12, answer.outputTokens());
        assertEquals(0, new BigDecimal("0.000042").compareTo(answer.costUsd()), "1000 tokens at $0.042/MTok");
        assertEquals(BOOK, answer.priceBookVersion());
        assertEquals("jev-latest", answer.requestBody().path("model").asText());
    }

    @Test
    void openRouterCall_postsToAlphaDecisionsAndPricesUnderTheTypeSafeBookKey() throws Exception {
        stub(response(200, answer(",\"cost\":0.00005")));

        DecisionAnswer answer = client().decide("p1", "frustration", openrouter(), request());

        HttpRequest sent = sent(1).get(0);
        assertEquals(URI.create("https://openrouter.ai/api/alpha/decisions"), sent.uri());
        assertEquals(List.of("Bearer or-key"), sent.headers().allValues("Authorization"));
        assertEquals(
                0,
                new BigDecimal("0.000042").compareTo(answer.costUsd()),
                "the book prices it, not the provider's usage.cost");
        assertEquals(0.00005, answer.responseBody().path("usage").path("cost").doubleValue());
        verify(accountant)
                .recordDecisionCall(
                        eq("p1"),
                        eq("frustration"),
                        eq("typesafe/jev-latest"),
                        eq(1000),
                        eq(12),
                        any(),
                        eq(BOOK),
                        anyInt());
    }

    @Test
    void pricingId_isTheTypeSafeBookKeyOnBothRoutes() {
        assertEquals("typesafe/jev-latest", JevDecisionClient.pricingId(typesafe()));
        assertEquals("typesafe/jev-latest", JevDecisionClient.pricingId(openrouter()));
    }

    @Test
    void requestBody_isModelStateAndQuestionsInTheDocumentedShape() throws Exception {
        ObjectNode body = client().requestBody(openrouter(), request());

        assertEquals("typesafe/jev-latest", body.path("model").asText());
        assertEquals(
                "that is wrong again",
                body.path("state").path("current_user_message").asText());
        var question = body.path("questions").path("user_stance");
        assertEquals("choice", question.path("type").asText());
        assertEquals("Which fits?", question.path("instructions").asText());
        List<String> keys = new ArrayList<>();
        question.path("criteria").fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("unhappy_with_assistant", "unhappy_other_cause", "neutral_or_positive"), keys);
    }

    @Test
    void a429ThenA200_retriesHonouringRetryAfter() throws Exception {
        stub(response(429, "", Map.of("Retry-After", List.of("3"))), response(200, answer("")));

        DecisionAnswer answer = client().decide("p1", "frustration", typesafe(), request());

        assertEquals(
                "unhappy_with_assistant", answer.answers().get("user_stance").choice());
        assertEquals(List.of(3_000L), sleeps);
        sent(2);
    }

    @Test
    void threeServerErrors_failTheCallAsUnavailableAfterBackingOff() throws Exception {
        stub(response(503, ""), response(502, ""), response(500, ""));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client().decide("p1", "frustration", typesafe(), request()));

        assertSame(DecisionError.PROVIDER_UNAVAILABLE, e.error());
        assertEquals(2, sleeps.size());
        assertEquals(true, sleeps.get(0) >= 1_000L && sleeps.get(0) <= 1_250L, "first back-off is 1s plus jitter");
        assertEquals(true, sleeps.get(1) >= 2_000L && sleeps.get(1) <= 2_500L, "second back-off is 2s plus jitter");
        sent(3);
    }

    @SuppressWarnings("unchecked")
    @Test
    void transportFailures_areRetriedLikeServerErrors() throws Exception {
        HttpResponse<String> ok = response(200, answer(""));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("reset"))
                .thenReturn(ok);

        DecisionAnswer answer = client().decide("p1", "frustration", typesafe(), request());

        assertEquals("jev-1.13-20260917", answer.respondedModel());
        assertEquals(1, sleeps.size());
    }

    @Test
    void a401_isRejectedWithoutRetrying() throws Exception {
        stub(response(401, "{\"error\":\"bad key\"}"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client().decide("p1", "frustration", typesafe(), request()));

        assertSame(DecisionError.PROVIDER_REJECTED, e.error());
        assertEquals(List.of(), sleeps);
        sent(1);
    }

    @Test
    void a400_isRefusedWithoutRetrying() throws Exception {
        stub(response(400, "{}"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client().decide("p1", "frustration", typesafe(), request()));

        assertSame(DecisionError.REQUEST_REFUSED, e.error());
        sent(1);
    }

    @Test
    void anAnswerMissingTheQuestion_isMalformedAndBooksNothing() throws Exception {
        stub(response(200, "{\"model\":\"jev\",\"answers\":{},\"usage\":{\"input_tokens\":5}}"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client().decide("p1", "frustration", typesafe(), request()));

        assertSame(DecisionError.MALFORMED_ANSWER, e.error());
        verify(accountant, times(0))
                .recordDecisionCall(anyString(), anyString(), anyString(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void aChoiceWithoutProbabilities_isMalformed() throws Exception {
        stub(response(
                200,
                "{\"model\":\"jev\",\"answers\":{\"user_stance\":{\"type\":\"choice\","
                        + "\"choice\":\"neutral_or_positive\"}}}"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client().decide("p1", "frustration", typesafe(), request()));

        assertSame(DecisionError.MALFORMED_ANSWER, e.error());
    }

    @Test
    void anUnpricedCall_isStillAnsweredWithNoCost() throws Exception {
        stub(response(200, answer("")));
        JevDecisionClient unpriced = new JevDecisionClient(
                http, mapper, OpenTelemetry.noop(), null, null, sleeps::add, Duration.ofSeconds(20), 3);

        DecisionAnswer answer = unpriced.decide("p1", "frustration", typesafe(), request());

        assertNull(answer.costUsd());
        assertNull(answer.priceBookVersion());
    }

    @Test
    void endpointFor_dropsATrailingV1AndHonoursAnOverride() {
        assertEquals(
                URI.create("https://openrouter.ai/api/alpha/decisions"),
                DecisionTarget.endpointFor(ModelProvider.OPENROUTER, "https://openrouter.ai/api/v1/"));
        assertEquals(
                URI.create("https://gw.example.com/v1/systemone"),
                DecisionTarget.endpointFor(ModelProvider.TYPESAFE, "https://gw.example.com"));
        assertThrows(TessaryException.class, () -> DecisionTarget.endpointFor(ModelProvider.OPENAI, null));
    }

    @Test
    void targetToString_neverPrintsTheKey() {
        assertEquals(false, typesafe().toString().contains("ts-key"));
    }
}
