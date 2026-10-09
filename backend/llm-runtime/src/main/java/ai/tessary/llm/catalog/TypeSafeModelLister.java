// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import java.util.List;

/**
 * TypeSafe documents no model-listing endpoint, so this returns the one decision model the catalog
 * offers. Pinned versions are not offered: the provider echoes the version that answered each call,
 * and that echo is what gets recorded.
 */
public final class TypeSafeModelLister implements ProviderModelLister {

    private static final List<ProviderModel> MODELS =
            List.of(new ProviderModel("jev-latest", "Jev (latest)", "TypeSafe"));

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        return MODELS;
    }
}
