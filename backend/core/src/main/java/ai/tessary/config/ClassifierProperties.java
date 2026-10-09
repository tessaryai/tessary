// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the async signal detection engine, bound from {@code tessary.classifier.*}.
 * Signals is a free, on-by-default surface, so the worker runs unconditionally (no enablement env
 * gate); it is strictly off the ingest hot path, so the substrate write path never depends on it.
 *
 * <p>Mirrors {@link ObserverProperties} / {@link SubstrateProperties}: defaults live here in code (no
 * yaml entries needed). The worker wakes on {@code tessary.classifier.heartbeat-ms} (bound directly by
 * {@code @Scheduled}) to claim due signal jobs via
 * {@code FOR UPDATE SKIP LOCKED} (running N backends is safe), sweeps up to {@link #batchSize}
 * observations past each signal's cursor, and dead-letters a job past {@link #maxAttempts}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.classifier")
public class ClassifierProperties {

    /** Max observations swept per signal-job claim round (the cursor advances by this window). */
    private int batchSize = 200;

    /**
     * The page an ENCODER-BACKED classifier (groundedness) sweeps at a time, in place of
     * {@link #batchSize}. Each such observation is a model call whose cost grows with the evidence it
     * carries, so a page is sized to finish well inside the lease on a CPU encoder; the sweep drains
     * page by page within the lease budget, persisting the cursor after each, so a backlog drains at
     * the encoder's own pace and a failure loses at most one page of work, never the whole window.
     */
    private int encoderBatchSize = 32;

    /** Lease duration for a claimed signal job; a worker that dies mid-sweep is reclaimed after this. */
    private long leaseSeconds = 300;

    /** Reclaim count cap: a job that keeps hanging/crashing the worker is dead-lettered, not retried forever. */
    private int maxAttempts = 5;

    /**
     * Floor between automatic revival attempts for a {@code dead}-lettered sweep job: one that fast-failed
     * {@link #maxAttempts} times in a row ({@code ClassifierJobRepository#markFailed}) or whose lease expired
     * that many times because the worker hung/crashed ({@code ClassifierJobRepository#failExhausted}). The
     * per-heartbeat enqueue call won't resurrect a {@code dead} job until this many seconds have passed
     * since it was dead-lettered, so a persistently-down backend (e.g. {@code /classify} unreachable) gets
     * one bounded retry probe per window instead of re-entering the failure loop every heartbeat.
     */
    private long deadLetterCooldownSeconds = 1800;

    // ---- triage scheduling (off unless `triage_automatic_enabled` targets the org) ----

    /**
     * How many samples a cause must have been observed over before triage will run on it. A cause seen
     * once is not yet a claim worth auditing, and this is the number that makes "triage everything" mean
     * "triage everything that has happened more than once".
     */
    private long triageMinTraceCount = 2;

    /**
     * Consecutive launcher-level failures before the triage drain parks itself.
     *
     * <p>Not one: a single connect failure is a blip and re-claiming is the right response. Three in a
     * row is the launcher, not the network. Counted as a RUN: any run that reaches the launcher
     * resets it, so an intermittent sidecar never accumulates its way to a trip.
     */
    private int triageBreakerFailures = 3;

    /**
     * How long the drain stays parked once tripped. There is no half-open probe: the cooldown expiring
     * IS the probe, so this is also how often a shut launcher is re-tested. Five minutes matches the
     * drain's own interval, which makes the parked state cost exactly one skipped tick.
     */
    private long triageBreakerCooldownSeconds = 300;

    /**
     * How long a triage job waits before re-checking a missing or unusable org credential. A
     * credential gap is not a failure of the finding and not a failure of the launcher, so it neither
     * spends an attempt nor trips {@link #triageBreakerFailures}: one org with no key must not park
     * every other org's drain. The job will keep failing identically until a human adds a credential,
     * so this is how often it is worth asking again.
     */
    private long triageConfigRetrySeconds = 1800;

    /**
     * Publicly reachable API base the triage sandbox calls back on for MCP reads (e.g.
     * https://app.tessary.ai), the same value {@code tessary.rca.agentic.mcp-base-url} carries for RCA.
     *
     * <p><b>Blank is a broken deployment, not a degraded mode.</b> The dossier is the detector's own
     * numbers and nothing else, no hydrated traces, so an agent with no MCP door cannot open a single
     * piece of the evidence it is auditing, and the only ruling it could reach is a restatement of the
     * claim. The engine refuses to run rather than produce one, which surfaces as retries and then a
     * dead letter (see {@code BehaviorTriageEngine}).
     */
    private String triageMcpBaseUrl = "";

    /**
     * Which {@code TriageSandbox} runs the Layer-2 agent. Only "e2b" ships today.
     */
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
