// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import java.util.List;

/**
 * TypeSafe has no listing endpoint. Pinned versions are not offered: the provider echoes the version that answered, and
 * that echo is recorded.
 */
public final class TypeSafeModelLister implements ProviderModelLister {

    private static final List<ProviderModel> MODELS =
            List.of(new ProviderModel("jev-latest", "Jev (latest)", "TypeSafe"));

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        return MODELS;
    }
}
