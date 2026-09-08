// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The one outbound GitHub call {@link GithubManifestController}'s callback needs: exchanging a
 * one-shot manifest {@code code} for the newly-registered App's own credentials. Pulled out as a
 * seam (mirrors {@code GitProviderClient}'s shape) purely so the controller's redirect/error
 * logic can be unit-tested without an HTTP call to {@code api.github.com} — {@link
 * GithubManifestHttpExchange} is the only real implementation.
 */
public interface GithubManifestExchange {

    /**
     * {@code POST https://api.github.com/app-manifests/{code}/conversions} — no auth, no body.
     * Returns the App object GitHub hands back ({@code id}, {@code pem}, {@code webhook_secret},
     * {@code slug}, {@code client_id}, {@code client_secret}). Throws {@code
     * GitError.MANIFEST_CONVERSION_FAILED} on any transport failure or non-2xx status — GitHub
     * rejects a reused/expired {@code code} this way, since the code is one-shot.
     */
    JsonNode convert(String code);
}
