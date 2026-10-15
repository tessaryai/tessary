// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The generic agent-sandbox run ({@code tessary.agentrun.*}), used by the {@code AGENT_RUN} lane. Blank
 * {@link #launcherUrl}, {@link #launcherApiKey} and {@link #mcpBaseUrl} fall back to the observer's launcher settings
 * and RCA's MCP origin, so a deployment that configured RCA has configured this too.
 */
@Component
@ConfigurationProperties(prefix = "tessary.agentrun")
public class AgentRunProperties {

    /** {@code e2b} (the launcher sidecar) or {@code docker}. */
    private String sandbox = "e2b";

    private String launcherUrl = "";

    private String launcherApiKey = "";

    private String mcpBaseUrl = "";

    /** A hard kill for a hung run, never a budget stated to the agent. */
    private long timeoutMs = 900_000;

    /** Passed to the sandbox as {@code max_turns}. */
    private int maxTurns = 40;

    public String getSandbox() {
        return sandbox;
    }

    public void setSandbox(String v) {
        this.sandbox = v;
    }

    public String getLauncherUrl() {
        return launcherUrl;
    }

    public void setLauncherUrl(String v) {
        this.launcherUrl = v;
    }

    public String getLauncherApiKey() {
        return launcherApiKey;
    }

    public void setLauncherApiKey(String v) {
        this.launcherApiKey = v;
    }

    public String getMcpBaseUrl() {
        return mcpBaseUrl;
    }

    public void setMcpBaseUrl(String v) {
        this.mcpBaseUrl = v;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long v) {
        this.timeoutMs = v;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int v) {
        this.maxTurns = v;
    }
}
