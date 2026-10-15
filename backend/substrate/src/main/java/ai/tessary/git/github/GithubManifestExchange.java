// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Exchanges a one-shot manifest {@code code} for the new App's credentials. A seam so {@link GithubManifestController}
 * can be tested without calling GitHub.
 */
public interface GithubManifestExchange {

    /**
     * {@code POST /app-manifests/{code}/conversions}, no auth. Throws {@code GitError.MANIFEST_CONVERSION_FAILED} on
     * transport failure or non-2xx, which is how GitHub rejects a reused or expired code.
     */
    JsonNode convert(String code);
}
