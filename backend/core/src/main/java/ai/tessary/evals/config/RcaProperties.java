// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the RCA worker, bound from {@code evals.rca.*} — mirrors {@link GraderRunProperties}.
 * Runs unconditionally: a job only exists when a person pressed "Run RCA" on a case, so there is no
 * hot-path exposure to gate and no budget beyond the per-press spend ledger.
 */
@Component
@ConfigurationProperties(prefix = "evals.rca")
public class RcaProperties {

    /** Jobs claimed per {@code FOR UPDATE SKIP LOCKED} round. */
    private int batchSize = 5;

    /** Lease duration for a claimed job. Must exceed the analysis stage's wall clock: every RCA runs
     *  a multi-minute agent session ({@link Agentic#timeoutMs}), so the lease is sized to that
     *  plus slack — else the worker reclaims and re-runs a healthy in-flight job. */
    private long leaseSeconds = 1200;

    /** Reclaim count cap: a job that keeps hanging/crashing the worker terminates as {@code failed}. */
    private int maxAttempts = 5;

    /** The sandboxed agent that performs every RCA — mirrors {@code ObserverProperties.Agentic}. */
    private final Agentic agentic = new Agentic();

    /**
     * Config for the RCA engine: an agent session in a fresh E2B microVM gets the project repo
     * cloned at HEAD, the evidence dossier as files, and (optionally) a live MCP connection back to
     * the platform — driven through the same Node launcher sidecar the observer uses. This is RCA's
     * only analysis path, so {@link #launcherUrl} is effectively required.
     */
    public static class Agentic {

        /** Which {@code RcaSandbox} runs the agent. Only "e2b" ships today. */
        private String sandbox = "e2b";

        /** Base URL of the Node launcher sidecar that drives the E2B SDK (e.g. http://launcher:8080). */
        private String launcherUrl = "";

        /** Bearer secret the launcher requires. */
        private String launcherApiKey = "";

        // NOTE: there is deliberately no `model` here. RCA rides the observer's launcher sidecar and
        // its bearer key, so it rides the observer's model too — see ObserverProperties.Agentic#model
        // (EVALS_OBSERVER_AGENTIC_MODEL). One microVM image, one Bedrock inference profile, one knob:
        // a separate EVALS_RCA_AGENTIC_MODEL only created a second thing to forget to set.

        /** Wall-clock cap on the sandbox run. Keep it below {@code evals.rca.lease-seconds} or the
         *  job is reclaimed mid-run. */
        private long timeoutMs = 900_000;

        // B (#994): the turn budget for this lane. E2bRcaSandbox threads it into the launcher POST
        // body as `max_turns`, which rca.js forwards into agent-stream.js's `config.agent.build.
        // maxSteps` — the SDK's own documented mechanism for forcing a text-only reply once the cap
        // is hit ("Maximum number of agentic iterations before forcing text-only response"), rather
        // than a hard kill that discards a partial verdict. See ObserverProperties.Agentic#maxTurns
        // (triage's sibling field) for the same note on what was and was not empirically verified.
        private int maxTurns = 40;

        /**
         * Publicly reachable API base the sandbox uses for MCP calls back into the platform (e.g.
         * https://app.tessary.ai), the same value {@code evals.classifier.triage-mcp-base-url} carries
         * for triage.
         *
         * <p><b>Blank is a broken deployment, not a degraded mode.</b> The dossier carries the
         * finding's claim, the detector's numbers and the measured checklist; every trace and span
         * behind them is fetched through this door. A run without it can only paraphrase the detector,
         * so the engine refuses and the report stamps {@code failed}.
         */
        private String mcpBaseUrl = "";

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

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long v) {
            this.timeoutMs = v;
        }

        public String getMcpBaseUrl() {
            return mcpBaseUrl;
        }

        public void setMcpBaseUrl(String v) {
            this.mcpBaseUrl = v;
        }

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int v) {
            this.maxTurns = v;
        }
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int v) {
        this.batchSize = v;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long v) {
        this.leaseSeconds = v;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int v) {
        this.maxAttempts = v;
    }

    public Agentic getAgentic() {
        return agentic;
    }
}
