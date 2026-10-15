// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.util.Optional;

/**
 * Builds a clone URL with a short-lived installation token embedded. Empty when no token can be minted. The URL must
 * never be logged.
 */
public final class GitCloneUrls {

    private GitCloneUrls() {}

    public static Optional<String> authenticated(GitProviderFactory providers, GitIntegrationRow integ) {
        String token;
        try {
            String header = providers.tokenService(integ.providerEnum()).authHeader(integ);
            token = header.startsWith("Bearer ") ? header.substring("Bearer ".length()) : header;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String apiHost = integ.host();
        String gitHost =
                (apiHost == null || apiHost.isBlank() || "api.github.com".equals(apiHost)) ? "github.com" : apiHost;
        return Optional.of("https://x-access-token:" + token + "@" + gitHost + "/" + integ.repoOwner() + "/"
                + integ.repoName() + ".git");
    }
}
