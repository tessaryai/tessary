// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

/**
 * Mints the credential a {@link GitProviderClient} attaches, hiding App installation tokens versus sealed
 * per-integration secrets.
 */
public interface GitTokenService {

    GitProvider provider();

    String authHeader(GitIntegrationRow integ);

    /**
     * The credential a sandbox clone gets. A provider that can mint a narrower token than {@link #authHeader} must
     * override this so the agent never holds write access. The default is for credentials that cannot be narrowed,
     * such as one personal access token.
     */
    default String cloneAuthHeader(GitIntegrationRow integ) {
        return authHeader(integ);
    }
}
