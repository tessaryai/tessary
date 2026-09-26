// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static ai.tessary.git.github.ScriptedHttpClient.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProviderClient.RepoAccess;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The GitHub REST client over a scripted transport, authenticated by a real PAT-mode token service: what
 * connect-time verification and head resolution send, and how each answer reads. The host is a TEST-NET-3
 * address so {@code UrlGuard} passes without DNS.
 */
class GithubClientTest {

    private static final String BASE = "https://203.0.113.40";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretBox box = secretBox();
    private final ScriptedHttpClient http = new ScriptedHttpClient();
    private final GithubClient client =
            new GithubClient(new GithubTokenService(new GithubAppProperties(), box, mapper), mapper, http);

    private static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 9);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    private GitIntegrationRow repo(String name) {
        return new GitIntegrationRow(
                "i1",
                "p1",
                "github",
                "203.0.113.40",
                "acme",
                name,
                "develop",
                box.seal("{\"token\":\"ghp_x\"}"),
                "t",
                "t");
    }

    /** A readable repo reports its default branch, falling back to {@code main} when GitHub names none. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"{\"default_branch\":\"trunk\"}|trunk", "{\"default_branch\":\"\"}|main", "{}|main"})
    void verifyAccess_readsTheDefaultBranch(String body, String branch) {
        http.on(BASE + "/repos/acme/my%20repo", response(200, body));

        assertEquals(new RepoAccess(branch), client.verifyAccess(repo("my repo")));
        assertEquals(
                "Bearer ghp_x",
                http.sent().get(0).headers().firstValue("Authorization").orElseThrow(),
                "verification reads with the integration's own credential");
    }

    /**
     * A refusal keeps its access verdict through the real round trip; any other non-2xx, or a 2xx whose body
     * is not JSON, is a provider failure rather than a verdict about access.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"404|{}|REPO_UNREACHABLE", "500|{}|PROVIDER_CALL_FAILED", "200|not json|PROVIDER_CALL_FAILED"})
    void verifyAccess_anUnreadableAnswerIsTyped(int status, String body, GitError expected) {
        http.on(BASE + "/repos/acme/web", response(status, body));

        TessaryException e = assertThrows(TessaryException.class, () -> client.verifyAccess(repo("web")));
        assertEquals(expected, e.error());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifyAccess_aTransportFailureIsAProviderFailure(boolean interrupted) {
        http.on(BASE + "/repos/acme/web", interrupted ? new InterruptedException() : new IOException("reset"));

        TessaryException e = assertThrows(TessaryException.class, () -> client.verifyAccess(repo("web")));
        assertEquals(GitError.PROVIDER_CALL_FAILED, e.error());
        assertEquals(interrupted, Thread.interrupted(), "the interrupt flag is restored for the caller");
    }

    @Test
    void resolveHeadSha_encodesTheBranchIntoOnePathSegment() {
        http.on(BASE + "/repos/acme/web/branches/feature%2Fx%20y", response(200, "{\"commit\":{\"sha\":\"abc123\"}}"));

        assertEquals("abc123", client.resolveHeadSha(repo("web"), "feature/x y"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void resolveHeadSha_withoutABranchUsesTheIntegrationDefault(String branch) {
        http.on(BASE + "/repos/acme/web/branches/develop", response(200, "{\"commit\":{\"sha\":\"def456\"}}"));

        assertEquals("def456", client.resolveHeadSha(repo("web"), branch));
    }
}
