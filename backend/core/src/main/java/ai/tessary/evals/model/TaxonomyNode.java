// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One taxonomy node. The skill emits two shapes:
 *  - flat (as documented): every node has its own row, parent linkage via {@code parent_id}.
 *  - nested: a parent carries its children directly under {@code subcategories}.
 * Both deserialize. Renderers should look at {@code subcategories} first; if
 * empty, fall back to flat parent_id lookup.
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
