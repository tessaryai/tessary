// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import java.util.List;

/**
 * Fetches one provider's model list, live, from that provider's own API. One implementation per
 * {@code ModelProvider} shape (not per provider — {@link OpenAiCompatModelLister} is shared by five
 * of them). {@code ModelCatalogFetchService} is the only caller; it owns caching, the per-(provider,
 * region) key, and the stale-fallback/no-credential-contributes-nothing behavior — a lister itself is
 * a pure "ask the vendor" call and throws {@link ModelListingException} on any failure rather than
 * deciding what a failure should degrade to.
 */
public interface ProviderModelLister {

    /**
     * The models {@code credential}'s provider reports right now.
     *
     * @throws ModelListingException the endpoint could not be reached or returned an unparseable body
     */
    List<ProviderModel> list(ResolvedCredential credential);
}
