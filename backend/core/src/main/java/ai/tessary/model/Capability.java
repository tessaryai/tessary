// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One capability the product exposes to its agents — a tool, a skill, an MCP server, a subagent —
 * as declared by the {@code .tessary/pipeline/capabilities.yaml} shard.
 *
 * <p>This is the inventory read out of the CODE, which is what makes it worth carrying. Everything
 * the platform knows about an agent's capabilities today is inferred from traffic: the behaviour-drift
 * detector learns a project's normal action skeleton from its own traces, so "a new capability
 * appeared" and "an established step quietly disappeared" are statistical inferences over what the
 * agent happened to do. Against a declared inventory they become a <em>diff</em> — a capability in
 * the code but never in traces is dead, one in traces but not the manifest is genuinely unexpected,
 * and today those two are indistinguishable.
 *
 * <p>{@code callSiteIds} optionally binds a capability to the call sites that can reach it; empty
 * means product-wide (or simply undeclared).
 *
 * @param name the capability's identifier as the code declares it.
 * @param kind {@code tool | skill | mcp_server | subagent}. Held as an open string, like {@code
 *     CallSite.shape}, so a new capability kind doesn't require a model change.
 * @param description what it does, for a human or a judge reading the manifest.
 * @param source a {@code file:line} pointer to the declaration.
 * @param callSiteIds the call sites that can reach this capability; empty when undeclared.
 */
public record Capability(
        String name,

        @Schema(allowableValues = {"tool", "skill", "mcp_server", "subagent"})
        String kind,

        @Nullable String description,
        @Nullable String source,
        @JsonProperty("call_site_ids") List<String> callSiteIds) {
    public Capability {
        if (kind == null) kind = "tool";
        callSiteIds = callSiteIds == null ? List.of() : callSiteIds;
    }
}
