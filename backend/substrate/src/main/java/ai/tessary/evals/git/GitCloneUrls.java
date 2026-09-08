// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

import java.util.Optional;

/**
 * Builds an authenticated clone URL with a short-lived installation token embedded
 * ({@code https://x-access-token:TOKEN@host/owner/repo.git}). Shared by the observer
 * (drift analysis) and synth (code grounding) so neither reaches into the other's
 * package. Returns empty when a token can't be minted (integration not authorized).
 * The token is short-lived — the URL must never be logged.
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
