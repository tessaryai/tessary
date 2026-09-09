// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Against a mocked {@link HttpClient} — see {@link OpenAiCompatModelListerTest}'s class javadoc for
 * why. Fixture ids are drawn from a real, live, unauthenticated read of
 * {@code https://openrouter.ai/api/v1/models} taken during #939 TASK 2's implementation, so the
 * SIX-MAKER-INTERSECT behavior under test is exercised against namespace strings OpenRouter actually
 * uses today, not invented ones. {@code credential.baseUrl()} is irrelevant — {@link
 * OpenRouterModelLister} ignores it entirely, same reasoning as {@link AnthropicModelLister}.
 */
class OpenRouterModelListerTest {

    private final HttpClient http = mock(HttpClient.class);

    private OpenRouterModelLister lister() {
        return new OpenRouterModelLister(http, new ObjectMapper());
    }

    private static ResolvedCredential cred(String apiKey) {
        return new ResolvedCredential(apiKey, null, null, null, null, false);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private HttpRequest capturedRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getValue();
    }

    @Test
    void intersectsTheCatalogWithTheSixSupportedMakers() throws Exception {
        stubResponse(
                200,
                "{\"data\":["
                        + "{\"id\":\"openai/gpt-5.5\",\"name\":\"OpenAI: GPT-5.5\"},"
                        + "{\"id\":\"anthropic/claude-sonnet-5\",\"name\":\"Anthropic: Claude Sonnet 5\"},"
                        + "{\"id\":\"meta/muse-spark-1.3\",\"name\":\"Meta: Muse Spark 1.3\"},"
                        + "{\"id\":\"mistralai/mistral-large\",\"name\":\"Mistral Large\"},"
                        + "{\"id\":\"z-ai/glm-5.2\",\"name\":\"Z.AI: GLM 5.2\"}"
                        + "]}");

        List<ProviderModel> models = lister().list(cred(null));

        assertEquals(
                List.of(
                        new ProviderModel("openai/gpt-5.5", "OpenAI: GPT-5.5", "OpenAI"),
                        new ProviderModel("anthropic/claude-sonnet-5", "Anthropic: Claude Sonnet 5", "Anthropic"),
                        new ProviderModel("z-ai/glm-5.2", "Z.AI: GLM 5.2", "Zhipu")),
                models,
                "Meta and Mistral must be dropped — neither is one of D6's six makers");
    }

    @Test
    void latestPointerAliasesResolveThroughTheTildePrefix() throws Exception {
        stubResponse(
                200, "{\"data\":[{\"id\":\"~anthropic/claude-haiku-latest\",\"name\":\"Claude Haiku (latest)\"}]}");

        List<ProviderModel> models = lister().list(cred(null));

        assertEquals(
                List.of(new ProviderModel("~anthropic/claude-haiku-latest", "Claude Haiku (latest)", "Anthropic")),
                models);
    }

    @Test
    void nameFallsBackToIdWhenAbsent() throws Exception {
        stubResponse(200, "{\"data\":[{\"id\":\"openai/gpt-5.5\"}]}");

        List<ProviderModel> models = lister().list(cred(null));

        assertEquals(List.of(new ProviderModel("openai/gpt-5.5", "openai/gpt-5.5", "OpenAI")), models);
    }

    @Test
    void needsNoAuthHeaderWhenCredentialCarriesNoKey() throws Exception {
        stubResponse(200, "{\"data\":[]}");

        lister().list(cred(null));

        assertTrue(capturedRequest().headers().allValues("Authorization").isEmpty());
    }

    @Test
    void sendsAuthHeaderWhenACredentialKeyIsPresent() throws Exception {
        stubResponse(200, "{\"data\":[]}");

        lister().list(cred("sk-or-test"));

        assertEquals(List.of("Bearer sk-or-test"), capturedRequest().headers().allValues("Authorization"));
    }

    @Test
    void non2xxStatus_throwsModelListingException() throws Exception {
        stubResponse(500, "{\"error\":\"boom\"}");

        assertThrows(ModelListingException.class, () -> lister().list(cred(null)));
    }

    @Test
    void emptyDataArray_neverFails() throws Exception {
        stubResponse(200, "{\"data\":[]}");

        assertFalse(lister().list(cred(null)).stream().findAny().isPresent());
    }
}
