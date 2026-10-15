// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Defaults live here in code; no yaml entries needed. Strictly off the ingest hot path. */
@Component
@ConfigurationProperties(prefix = "tessary.classifier")
public class ClassifierProperties {

    private int batchSize = 200;

    /**
     * Each encoder observation is a model call, so pages are sized to finish well inside the lease; a failure loses at
     * most one page.
     */
    private int encoderBatchSize = 32;

    private long leaseSeconds = 300;

    private int maxAttempts = 5;

    /**
     * A persistently-down backend gets one bounded retry probe per window instead of re-entering the failure loop every
     * heartbeat.
     */
    private long deadLetterCooldownSeconds = 1800;

    /** A cause seen once is not yet worth auditing. */
    private long triageMinTraceCount = 2;

    /** Counted as a run, so an intermittent sidecar never accumulates its way to a trip. */
    private int triageBreakerFailures = 3;

    /** No half-open probe: the cooldown expiring is the probe. */
    private long triageBreakerCooldownSeconds = 300;

    /** A credential gap spends no attempt and trips no breaker: one org with no key must not park everyone's drain. */
    private long triageConfigRetrySeconds = 1800;

    /**
     * Blank is a broken deployment, not a degraded mode: without MCP the agent cannot open any evidence, so the engine
     * refuses to run.
     */
    private String triageMcpBaseUrl = "";

    private String triageSandbox = "e2b";

    public int getBatchSize() {
        return batchSize;
    }

    public int getEncoderBatchSize() {
        return encoderBatchSize;
    }

    public void setEncoderBatchSize(int v) {
        this.encoderBatchSize = v;
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

    public long getDeadLetterCooldownSeconds() {
        return deadLetterCooldownSeconds;
    }

    public void setDeadLetterCooldownSeconds(long v) {
        this.deadLetterCooldownSeconds = v;
    }

    public long getTriageMinTraceCount() {
        return triageMinTraceCount;
    }

    public void setTriageMinTraceCount(long v) {
        this.triageMinTraceCount = v;
    }

    public int getTriageBreakerFailures() {
        return triageBreakerFailures;
    }

    public void setTriageBreakerFailures(int v) {
        this.triageBreakerFailures = v;
    }

    public long getTriageBreakerCooldownSeconds() {
        return triageBreakerCooldownSeconds;
    }

    public void setTriageBreakerCooldownSeconds(long v) {
        this.triageBreakerCooldownSeconds = v;
    }

    public long getTriageConfigRetrySeconds() {
        return triageConfigRetrySeconds;
    }

    public void setTriageConfigRetrySeconds(long v) {
        this.triageConfigRetrySeconds = v;
    }

    public String getTriageMcpBaseUrl() {
        return triageMcpBaseUrl;
    }

    public void setTriageMcpBaseUrl(String v) {
        this.triageMcpBaseUrl = v;
    }

    public String getTriageSandbox() {
        return triageSandbox;
    }

    public void setTriageSandbox(String v) {
        this.triageSandbox = v;
    }
}
