// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the RCA worker, bound from {@code tessary.rca.*}. Runs unconditionally: a job exists only when a person
 * pressed "Run RCA".
 */
@Component
@ConfigurationProperties(prefix = "tessary.rca")
public class RcaProperties {

    private int batchSize = 5;

    /**
     * Must exceed the agent session's wall clock ({@link Agentic#timeoutMs}), or the worker reclaims a healthy
     * in-flight job.
     */
    private long leaseSeconds = 1200;

    /** A job that keeps hanging or crashing the worker terminates as {@code failed}. */
    private int maxAttempts = 5;

    private final Agentic agentic = new Agentic();

    /** RCA's only analysis path, so {@link #launcherUrl} is effectively required. */
    public static class Agentic {

        /** Only "e2b" ships today. */
        private String sandbox = "e2b";

        private String launcherUrl = "";

        private String launcherApiKey = "";

        // No `model` here on purpose: RCA rides the observer's launcher and model (TESSARY_OBSERVER_AGENTIC_MODEL).

        /** Keep below {@code tessary.rca.lease-seconds} or the job is reclaimed mid-run. */
        private long timeoutMs = 900_000;

        // Sent as `max_turns`, which forces a text-only reply at the cap rather than a hard kill that discards a
        // partial verdict.
        private int maxTurns = 40;

        /**
         * Blank is a broken deployment: every trace behind the dossier is fetched through this door, so the engine
         * refuses and the report is stamped {@code failed}.
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
