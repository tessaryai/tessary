// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.util.Optional;

/**
 * Builds a clone URL with the provider's clone credential embedded: for a GitHub App, a short-lived token that can only
 * read contents. Empty when no such token can be minted; it never falls back to the write token. The URL must never be
 * logged.
 */
public final class GitCloneUrls {

    private GitCloneUrls() {}

    public static Optional<String> authenticated(GitProviderFactory providers, GitIntegrationRow integ) {
        String token;
        try {
            String header = providers.tokenService(integ.providerEnum()).cloneAuthHeader(integ);
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
