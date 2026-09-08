// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Against a mocked {@link HttpClient} rather than a real socket — {@code com.sun.net.httpserver} is
 * banned by this repo's {@code forbiddenapis} check (a non-portable/internal JDK package), so a
 * loopback server is not an option here. The mock still exercises the actual {@link HttpRequest} the
 * lister builds (captured and asserted on below) and the actual response-parsing code, which a
 * lower-fidelity stub of {@code list()} itself would not catch a bug in.
 */
class OpenAiCompatModelListerTest {

    private final HttpClient http = mock(HttpClient.class);

    private OpenAiCompatModelLister lister() {
        return new OpenAiCompatModelLister(http, new ObjectMapper(), "OpenAI");
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
    void parsesEveryIdFromTheDataArray_displayNameDefaultsToTheId() throws Exception {
        stubResponse(200, "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5.5\"},{\"id\":\"gpt-5.4-mini\"}]}");

        List<ProviderModel> models = lister().list(
                        new ResolvedCredential("sk-test-key", "https://api.openai.com/v1", null, null, null, false));

        assertEquals(
                List.of(
                        new ProviderModel("gpt-5.5", "gpt-5.5", "OpenAI"),
                        new ProviderModel("gpt-5.4-mini", "gpt-5.4-mini", "OpenAI")),
                models);
    }

    @Test
    void sendsTheApiKeyAsABearerHeaderAgainstTheModelsPath() throws Exception {
        stubResponse(200, "{\"data\":[]}");

        lister().list(new ResolvedCredential("sk-test-key", "https://api.example.com/v1", null, null, null, false));

        HttpRequest request = capturedRequest();
        assertEquals(List.of("Bearer sk-test-key"), request.headers().allValues("Authorization"));
        assertEquals("https://api.example.com/v1/models", request.uri().toString());
    }

    @Test
    void noApiKey_sendsNoAuthorizationHeader() throws Exception {
        stubResponse(200, "{\"data\":[]}");

        lister().list(new ResolvedCredential(null, "https://api.openai.com/v1", null, null, null, false));

        assertTrue(capturedRequest().headers().allValues("Authorization").isEmpty());
    }

    @Test
    void skipsAnEntryWithNoId() throws Exception {
        stubResponse(200, "{\"data\":[{\"id\":\"gpt-5.5\"},{\"object\":\"model\"}]}");

        List<ProviderModel> models =
                lister().list(new ResolvedCredential(null, "https://api.openai.com/v1", null, null, null, false));

        assertEquals(List.of(new ProviderModel("gpt-5.5", "gpt-5.5", "OpenAI")), models);
    }

    @Test
    void emptyDataArray_returnsEmptyNotNull() throws Exception {
        stubResponse(200, "{\"data\":[]}");

        assertEquals(
                List.of(),
                lister().list(new ResolvedCredential(null, "https://api.openai.com/v1", null, null, null, false)));
    }

    @Test
    void non2xxStatus_throwsModelListingException() throws Exception {
        stubResponse(401, "{\"error\":\"unauthorized\"}");

        assertThrows(
                ModelListingException.class,
                () -> lister().list(new ResolvedCredential(
                        "bad-key", "https://api.openai.com/v1", null, null, null, false)));
    }

    @Test
    void ioExceptionFromTheTransport_wrapsIntoModelListingException() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));

        assertThrows(
                ModelListingException.class,
                () -> lister().list(new ResolvedCredential(
                        "sk-test", "https://api.openai.com/v1", null, null, null, false)));
    }

    @Test
    void noBaseUrl_throwsModelListingExceptionRatherThanNpe() {
        assertThrows(
                ModelListingException.class,
                () -> lister().list(new ResolvedCredential("sk-test", null, null, null, null, false)));
    }

    @Test
    void trailingSlashOnBaseUrl_doesNotProduceADoubleSlash() throws Exception {
        stubResponse(200, "{\"data\":[]}");

        lister().list(new ResolvedCredential(null, "https://api.openai.com/v1/", null, null, null, false));

        String path = capturedRequest().uri().toString();
        assertTrue(path.endsWith("/v1/models") && !path.contains("//models"), path);
    }
}
