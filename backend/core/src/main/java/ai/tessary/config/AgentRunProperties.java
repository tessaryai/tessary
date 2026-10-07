// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The generic agent-sandbox run ({@code agentrun/}), bound from {@code tessary.agentrun.*}: one
 * coding-agent session in a fresh sandbox over a caller-supplied prompt, file set and schema, with
 * the project's repository and the platform's MCP surface in reach. The {@code AUTHORING} lane runs
 * through it.
 *
 * <p>{@link #launcherUrl}, {@link #launcherApiKey} and {@link #mcpBaseUrl} are blank by default and
 * fall back, in that order, to {@code tessary.observer.agentic.launcher-url} /
 * {@code launcher-api-key} and {@code tessary.rca.agentic.mcp-base-url}: every agentic lane rides the
 * same launcher sidecar and the same public origin, so a deployment that configured RCA has
 * configured this too, and an extra variable would be one more thing to forget to set.
 */
@Component
@ConfigurationProperties(prefix = "tessary.agentrun")
public class AgentRunProperties {

    /** Which {@code AgentRunSandbox} runs the agent: {@code e2b} (the launcher sidecar) or {@code docker}. */
    private String sandbox = "e2b";

    /** Base URL of the launcher sidecar; blank rides the observer's. */
    private String launcherUrl = "";

    /** Bearer secret the launcher requires; blank rides the observer's. */
    private String launcherApiKey = "";

    /** Publicly reachable API origin the sandbox calls MCP back on; blank rides RCA's. */
    private String mcpBaseUrl = "";

    /** Wall-clock cap on one run. A hard kill for a hung run, never a budget stated to the agent. */
    private long timeoutMs = 900_000;

    /** The agent's turn budget, threaded to the sandbox as {@code max_turns} like the other agent lanes. */
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
