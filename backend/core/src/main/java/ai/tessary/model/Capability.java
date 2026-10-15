// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One agent capability (tool, skill, MCP server, subagent) declared in {@code capabilities.yaml}. A declared inventory
 * turns "appeared" and "disappeared" from statistical inference into a diff against traffic. @param kind an open
 * string, like {@code CallSite.shape} @param source a {@code file:line} pointer @param callSiteIds empty when
 * undeclared or product-wide
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
