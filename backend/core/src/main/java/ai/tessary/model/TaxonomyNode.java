// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The skill emits nodes flat (linked by {@code parent_id}) or nested (under {@code subcategories}); both deserialize.
 * Renderers should check {@code subcategories} first.
 */
public record TaxonomyNode(
        String id,
        String name,
        String description,
        @JsonProperty("parent_id") @Nullable String parentId,
        @JsonProperty("example_call_site_ids") List<String> exampleCallSiteIds,
        @JsonProperty("example_chain_ids") List<String> exampleChainIds,
        String kind,
        List<TaxonomyNode> subcategories) {
    public TaxonomyNode {
        exampleCallSiteIds = exampleCallSiteIds == null ? List.of() : exampleCallSiteIds;
        exampleChainIds = exampleChainIds == null ? List.of() : exampleChainIds;
        subcategories = subcategories == null ? List.of() : subcategories;
    }
}
