// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * Against a mocked {@link HttpClient} — see {@link OpenAiCompatModelListerTest}'s class javadoc for
 * why ({@code com.sun.net.httpserver} is forbidden by this repo's static-analysis policy). Anthropic's
 * own shape ({@code display_name}, cursor pagination) is different enough from the OpenAI-compat one
 * that it earns its own fixture rather than sharing one.
 *
 * <p>{@code credential.baseUrl()} is irrelevant here — {@link AnthropicModelLister} ignores it
 * entirely (see that class's own javadoc: Anthropic has exactly one real host), so every test below
 * passes {@code null} for it and asserts against the real {@code https://api.anthropic.com/v1/models}
 * URL the lister actually builds.
 *
 * <p>Every response mock ({@link #page}) is built and fully stubbed BEFORE it is handed to {@code
 * http}'s own stub — Mockito's "unfinished stubbing" detector otherwise misfires when a second {@code
 * when(...)} call (from building the next argument) interleaves with the first, still-open one.
 */
class AnthropicModelListerTest {

    private final HttpClient http = mock(HttpClient.class);

    private AnthropicModelLister lister() {
        return new AnthropicModelLister(http, new ObjectMapper());
    }

    private static ResolvedCredential cred(String apiKey) {
        return new ResolvedCredential(apiKey, null, null, null, null, false);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> page(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void stub(HttpResponse<String>... pages) throws Exception {
        var stubbing = when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
        for (HttpResponse<String> p : pages) {
            stubbing = stubbing.thenReturn(p);
        }
    }

    private HttpRequest capturedRequest(int callIndex) throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(callIndex + 1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getAllValues().get(callIndex);
    }

    @Test
    void parsesIdAndDisplayName_sendsNativeAnthropicHeadersAgainstTheRealHost() throws Exception {
        stub(page(
                200,
                "{\"data\":[{\"id\":\"claude-sonnet-5\",\"display_name\":\"Claude Sonnet 5\"}],"
                        + "\"has_more\":false}"));

        List<ProviderModel> models = lister().list(cred("sk-ant-test"));

        assertEquals(List.of(new ProviderModel("claude-sonnet-5", "Claude Sonnet 5", "Anthropic")), models);
        HttpRequest request = capturedRequest(0);
        assertEquals(List.of("sk-ant-test"), request.headers().allValues("x-api-key"));
        assertTrue(!request.headers().allValues("anthropic-version").isEmpty());
        assertEquals("https://api.anthropic.com/v1/models", request.uri().toString());
    }

    @Test
    void displayNameDefaultsToId_whenAbsent() throws Exception {
        stub(page(200, "{\"data\":[{\"id\":\"claude-x\"}],\"has_more\":false}"));

        List<ProviderModel> models = lister().list(cred("sk-ant-test"));

        assertEquals(List.of(new ProviderModel("claude-x", "claude-x", "Anthropic")), models);
    }

    @Test
    void followsHasMoreCursorAcrossPages() throws Exception {
        stub(
                page(200, "{\"data\":[{\"id\":\"claude-a\"}],\"has_more\":true,\"last_id\":\"claude-a\"}"),
                page(200, "{\"data\":[{\"id\":\"claude-b\"}],\"has_more\":false}"));

        List<ProviderModel> models = lister().list(cred("sk-ant-test"));

        assertEquals(
                List.of(
                        new ProviderModel("claude-a", "claude-a", "Anthropic"),
                        new ProviderModel("claude-b", "claude-b", "Anthropic")),
                models);
        assertTrue(capturedRequest(1).uri().toString().contains("after_id=claude-a"));
    }

    @Test
    void aBuggyHasMoreThatNeverGoesFalse_isBoundedByMaxPagesNotAnInfiniteLoop() throws Exception {
        stub(page(200, "{\"data\":[{\"id\":\"claude-x\"}],\"has_more\":true,\"last_id\":\"claude-x\"}"));

        lister().list(cred("sk-ant-test"));

        verify(http, atMost(6)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void missingApiKey_throwsModelListingExceptionWithoutARequest() throws Exception {
        assertThrows(ModelListingException.class, () -> lister().list(cred(null)));

        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void non2xxStatus_throwsModelListingException() throws Exception {
        stub(page(403, "{\"error\":\"forbidden\"}"));

        assertThrows(ModelListingException.class, () -> lister().list(cred("sk-ant-bad")));
    }

    @Test
    void ioExceptionFromTheTransport_wrapsIntoModelListingException() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));

        assertThrows(ModelListingException.class, () -> lister().list(cred("sk-ant-test")));
    }
}
