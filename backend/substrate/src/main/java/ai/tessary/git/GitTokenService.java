// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

/**
 * Mints the credential a {@link GitProviderClient} attaches, hiding App installation tokens versus sealed
 * per-integration secrets.
 */
public interface GitTokenService {

    GitProvider provider();

    String authHeader(GitIntegrationRow integ);
}
