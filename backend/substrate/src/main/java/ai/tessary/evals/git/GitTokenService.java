// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

/**
 * Mints the ephemeral credential a {@link GitProviderClient} attaches to API
 * calls for one integration. Abstracts "platform-app + installation id"
 * (GitHub App tokens) from "per-integration sealed secret" (PAT/OAuth).
 */
public interface GitTokenService {

    GitProvider provider();

    /** A ready-to-send {@code Authorization} header value for this integration. */
    String authHeader(GitIntegrationRow integ);
}
