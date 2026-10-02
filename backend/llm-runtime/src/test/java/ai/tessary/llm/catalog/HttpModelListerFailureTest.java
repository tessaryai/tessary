// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The failure contract every HTTP lister shares: whatever goes wrong on the wire reaches the caller as a {@link
 * ModelListingException}, and an interrupt survives it. Each lister keeps its own catch blocks, so each is driven
 * through every failure. Its request shape and parsing stay in its own test class.
 */
class HttpModelListerFailureTest {

    private final HttpClient http = mock(HttpClient.class);

    enum Lister {
        ANTHROPIC(
                http -> new AnthropicModelLister(http, new ObjectMapper(), Duration.ofSeconds(5)),
                new ResolvedCredential("sk-ant-test", null, null, null, null, false),
                403,
                "{\"error\":\"forbidden\"}"),
        OPENAI_COMPAT(
                http -> new OpenAiCompatModelLister(http, new ObjectMapper(), "OpenAI", Duration.ofSeconds(5)),
                new ResolvedCredential("sk-test", "https://api.openai.com/v1", null, null, null, false),
                401,
                "{\"error\":\"unauthorized\"}"),
        OPENROUTER(
                http -> new OpenRouterModelLister(http, new ObjectMapper(), Duration.ofSeconds(5)),
                new ResolvedCredential(null, null, null, null, null, false),
                500,
                "{\"error\":\"boom\"}");

        final Function<HttpClient, ProviderModelLister> build;
        final ResolvedCredential credential;
        final int refusalStatus;
        final String refusalBody;

        Lister(
                Function<HttpClient, ProviderModelLister> build,
                ResolvedCredential credential,
                int refusalStatus,
                String refusalBody) {
            this.build = build;
            this.credential = credential;
            this.refusalStatus = refusalStatus;
            this.refusalBody = refusalBody;
        }
    }

    interface Failure {
        void stub(HttpClient http, Lister lister) throws Exception;
    }

    static Stream<Arguments> failures() {
        Failure non2xx = (http, lister) -> answer(http, lister.refusalStatus, lister.refusalBody);
        Failure transport =
                (http, lister) -> when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                        .thenThrow(new IOException("connection reset"));
        Failure unparseable = (http, lister) -> answer(http, 200, "<html>maintenance</html>");
        return Arrays.stream(Lister.values())
                .flatMap(lister -> Stream.of(
                        Arguments.of(lister, Named.of("a non-2xx status", non2xx)),
                        Arguments.of(lister, Named.of("an IOException from the transport", transport)),
                        Arguments.of(lister, Named.of("an unparseable body", unparseable))));
    }

    @SuppressWarnings("unchecked")
    private static void answer(HttpClient http, int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("failures")
    void aFailedFetchIsAListingFailure(Lister lister, Failure failure) throws Exception {
        failure.stub(http, lister);

        assertThrows(ModelListingException.class, () -> lister.build.apply(http).list(lister.credential));
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @EnumSource(Lister.class)
    void anInterruptedFetchIsAListingFailureThatKeepsTheInterrupt(Lister lister) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException());

        boolean interrupted;
        try {
            assertThrows(
                    ModelListingException.class, () -> lister.build.apply(http).list(lister.credential));
        } finally {
            interrupted = Thread.interrupted();
        }

        assertTrue(interrupted, "the caller's interrupt must survive the failed fetch");
    }
}
