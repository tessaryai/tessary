// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The authenticated clone URL RCA checks a repo out with: the token from the provider's auth header, and the
 * git host, which for github.com is not the API host the integration stores.
 */
class GitCloneUrlsTest {

    /** A token service that answers every integration with one fixed header, or fails. */
    private record FixedHeader(String header) implements GitTokenService {
        @Override
        public GitProvider provider() {
            return GitProvider.GITHUB;
        }

        @Override
        public String authHeader(GitIntegrationRow integ) {
            if (header == null) throw new IllegalStateException("no credentials");
            return header;
        }
    }

    private static GitIntegrationRow repo(String host) {
        return new GitIntegrationRow("i1", "p1", "github", host, "acme", "web", "main", "enc", "t", "t");
    }

    private static Optional<String> url(String header, String host) {
        return GitCloneUrls.authenticated(
                new GitProviderFactory(List.of(), List.of(new FixedHeader(header))), repo(host));
    }

    /**
     * github.com clones from github.com even though its API lives on api.github.com; an Enterprise host is
     * both. The token goes in bare, whether or not the header carried a Bearer prefix.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "Bearer ghs_1, NULL, https://x-access-token:ghs_1@github.com/acme/web.git",
                "Bearer ghs_1, '  ', https://x-access-token:ghs_1@github.com/acme/web.git",
                "Bearer ghs_1, api.github.com, https://x-access-token:ghs_1@github.com/acme/web.git",
                "ghp_raw, git.corp.example, https://x-access-token:ghp_raw@git.corp.example/acme/web.git"
            })
    void authenticated_buildsACloneUrlOnTheGitHost(String header, String host, String expected) {
        assertEquals(Optional.of(expected), url(header, host));
    }
}
