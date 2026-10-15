// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import java.util.List;

/**
 * Fetches one provider's model list live. One implementation per {@code ModelProvider} shape. Throws
 * {@link ModelListingException} on any failure; {@code ModelCatalogFetchService} owns caching and degradation.
 */
public interface ProviderModelLister {

    /** @throws ModelListingException unreachable endpoint or unparseable body */
    List<ProviderModel> list(ResolvedCredential credential);
}
