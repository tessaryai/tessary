// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The generic agent-sandbox run ({@code tessary.agentrun.*}), used by the {@code AGENT_RUN} lane. Blank
 * {@link #launcherUrl}, {@link #launcherApiKey} and {@link #mcpBaseUrl} fall back to the observer's launcher settings
 * and RCA's MCP origin, so a deployment that configured RCA has configured this too.
 */
@ConfigurationProperties(prefix = "tessary.agentrun")
public record AgentRunProperties(

        /** {@code e2b} (the launcher sidecar) or {@code docker}. */
        @DefaultValue("e2b") String sandbox,

        @DefaultValue("") String launcherUrl,

        @DefaultValue("") String launcherApiKey,

        @DefaultValue("") String mcpBaseUrl,

        /** A hard kill for a hung run, never a budget stated to the agent. */
        @DefaultValue("900000") long timeoutMs,

        /** Passed to the sandbox as {@code max_turns}. */
        @DefaultValue("40") int maxTurns) {}
