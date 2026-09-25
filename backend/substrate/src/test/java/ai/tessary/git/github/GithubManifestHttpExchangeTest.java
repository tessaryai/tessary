// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static ai.tessary.git.github.ScriptedHttpClient.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The manifest-flow conversion over a scripted transport: the one-shot code GitHub hands back is exchanged
 * for the new App's credentials, and every way that exchange can fail is MANIFEST_CONVERSION_FAILED.
 */
class GithubManifestHttpExchangeTest {

    private static final String API = "https://203.0.113.50";

    private final ScriptedHttpClient http = new ScriptedHttpClient();
    private final GithubManifestHttpExchange exchange = new GithubManifestHttpExchange(new ObjectMapper(), http, API);

    @Test
    void convert_postsTheEncodedCodeAndReturnsTheAppCredentials() {
        http.on(API + "/app-manifests/a%2Fb/conversions", response(201, "{\"id\":42,\"slug\":\"acme-evals\"}"));

        var app = exchange.convert("a/b");

        assertEquals(42, app.path("id").asInt());
        assertEquals("acme-evals", app.path("slug").asText());
        assertEquals("POST", http.sent().get(0).method(), "GitHub's conversion endpoint only takes POST");
    }

    /**
     * A reused or expired code (GitHub's 422), a body that is not JSON, and a transport failure all fail the
     * conversion; an interrupt is handed back to the caller rather than swallowed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"422", "not json", "io", "interrupt"})
    void convert_failuresAreManifestConversionFailed(String failure) {
        Object answer =
                switch (failure) {
                    case "422" -> response(422, "{\"message\":\"Not Found\"}");
                    case "not json" -> response(201, "not json");
                    case "io" -> new IOException("reset");
                    default -> new InterruptedException();
                };
        http.on(API + "/app-manifests/c/conversions", answer);

        TessaryException e = assertThrows(TessaryException.class, () -> exchange.convert("c"));
        assertEquals(GitError.MANIFEST_CONVERSION_FAILED, e.error());
        assertEquals("interrupt".equals(failure), Thread.interrupted());
    }
}
