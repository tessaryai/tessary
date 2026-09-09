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

    /**
     * Classifier signals. Cold-start training labels a bounded sample of recent observations with a
     * platform-funded LLM (the whole point is to keep the LLM off the per-trace hot path, bound by COUNT,
     * never by truncating trace text), embeds them, and trains a per-project centroid model.
     */
    private int classifierSampleLimit = 100;

    /** Minimum labeled examples (across both classes) required before a centroid model can be trained. */
    private int classifierMinExamples = 4;

    /**
     * Character budget for a THREAD-scoped classifier's reduced conversation thread. Turns are already
     * compact (assistant prose capped, tool payloads collapsed to terse markers), so the budget bounds
     * how much history rides through {@link
     * ai.tessary.classifier.substrate.ConversationThreadRenderer#reduceThread}: over budget it keeps the
     * baseline head (earliest turn + earliest failure marker) and the most-recent {@link
     * #threadRecentTurns} turns, thins the middle, and marks the drop. ~8000 chars is ~2k tokens; the
     * encoder is not finally pinned, so this stays a config knob.
     */
    private int threadCharBudget = 8000;

    /**
     * How many of the most-recent turns the reduction always keeps intact when it must evict to fit
     * {@link #threadCharBudget}. Recency carries the scored signal; the baseline head (earliest turn +
     * earliest failure marker) is kept alongside it and the middle is thinned.
     */
    private int threadRecentTurns = 3;

    /**
     * Row cap on how many of a session's most-recent observations a THREAD assembly loads before
     * the character budget trims further. Bounds the per-observation session read so one enormous
     * session can't be walked in full on the sweep.
     */
    private int threadMaxObservations = 40;

    /**
     * How stale an idle behaviour profile may get before the periodic fit re-runs it anyway. A trace
     * delta is the fast path, but it cannot be the only one: graduation and quarantine expiry are
     * driven by wall clock, not by arrivals, so an epoch that stops receiving traces must still be
     * fitted occasionally or a gram that earned normality would never be promoted.
     *
     * <p>This is a ceiling, not a period: the worker jitters each profile's slot by a stable hash of
     * its id over {@code (interval/2, interval]}, so idle profiles that came due together do not
     * re-form one wide burst at a longer period. Raising it spreads the herd wider as well as later.
     */
    private long behaviorFitIdleIntervalMs = 21_600_000; // 6h

    /**
     * How soon after a LEARNING profile's corpus stops growing its next fit is due, until it has taken
     * the sustained quiet fits arming needs. Saturation on a corpus that arrived and then stopped is
     * measured by exactly those quiet fits, and on the idle lane alone they land 3 to 12 hours out;
     * this lane makes each due fit land on the next tick instead, then hands the profile back to the
     * idle interval. The delay is a floor; the tick is the period.
     */
    private long behaviorFitSettleDelayMs = 60_000;

    // ---- triage scheduling (off unless `triage_automatic_enabled` targets the org) ----

    /**
     * How often the triage scheduler looks for findings to rule on. Slow on purpose: nothing is waiting
     * on the answer, the eligible set is bounded by distinct causes rather than by traffic, and a tick
     * that finds nothing is the expected outcome on every project that has not opted in.
     */
    private long triageIntervalMs = 900_000; // 15m

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
     * How many times a CLOSED finding's cause must fire again before it goes back through triage.
     *
     * <p>Recurrence is the recovery, and this is its threshold. Triage closes on {@code negative} and on
     * {@code unclear}, so a wrongly-closed finding is not a lost one: its cause keeps firing, the
     * counter climbs, and at this many firings within {@link #triageReopenWindowHours} the ruling is
     * cleared and a second look is scheduled. A finding that has already had its two looks and closed
     * again opens a case directly instead: the agent has said its piece twice, and the third time a
     * person reads it.
     */
    private long triageReopenRecurrences = 3;

    /**
     * The window {@link #triageReopenRecurrences} must fall within, counted back from now against
     * {@code last_seen_at}. Three firings spread over a quarter is a cause that recovered and returned,
     * not a ruling that was wrong; a week is close enough together to be the same spell.
     */
    private int triageReopenWindowHours = 168; // 7d

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
     * Which {@code TriageSandbox} runs the Layer-2 agent. Only "e2b" ships today. Its own knob, not a
     * reuse of {@code ObserverProperties.Agentic#sandbox}: that property's javadoc scopes it to
     * selecting an {@code AnalysisSandbox}, and triage needs to be configurable independently of the
     * observer's drift analysis once a second sandbox implementation exists for either lane.
     */
    private String triageSandbox = "e2b";

    /** SOP-conformance serving knobs, bound from {@code tessary.classifier.conformance.*}. */
    private final Conformance conformance = new Conformance();

    /**
     * Where the SOP-conformance sweep's sentence encoder comes from. The conformance artifact bundle
     * names a frozen checkpoint (e.g. {@code Alibaba-NLP/gte-large-en-v1.5}) and deliberately does
     * not bundle its weights; {@link #encoderMode} picks who runs the forward pass. {@code http} (the
     * default, and the production posture) calls the classify-service's {@code POST /embed} endpoint
     * via {@code HttpConformanceEncoder}, reusing the endpoint the encoder classifiers already call;
     * the checkpoint must be present in the service's {@code embedders.json} or the sweep fails
     * loudly. {@code in-jvm} runs an in-process ONNX Runtime pass instead; see {@link #encoderMode}
     * for what this build does with that mode.
     *
     * <p>Everything is unset by default beyond the mode: the classifier seeds disabled behind
     * {@code sop_conformance_enabled}, and an enabled deterministic bundle (no heads) never touches
     * the encoder, so nothing needs a model or an endpoint until a head-carrying bundle is deployed,
     * at which point an unconfigured encoder fails the sweep loudly rather than scoring on garbage.
     */
    public static class Conformance {

        /**
         * Which {@code ConformanceEncoder} serves the sweep
         * ({@code tessary.classifier.conformance.encoder-mode}): {@code http} (default: the
         * classify-service {@code /embed} endpoint) or {@code in-jvm} (an in-process ONNX pass). Any
         * other value leaves no encoder bean, since a typo must never silently pick an implementation.
         *
         * <p>This build's only implementation of the encoder port is {@code HttpConformanceEncoder},
         * conditional on {@code http}, so {@code in-jvm} is indistinguishable from a typo here and is
         * treated as one: no encoder bean is registered, and the first caller that needs one fails
         * naming this property. It is not a startup failure by itself, since no caller in this build
         * holds the port unconditionally.
         */
        private String encoderMode = "http";

        /**
         * {@code in-jvm} mode only: directory holding the ONNX export of the checkpoint:
         * {@code tokenizer.json} plus {@code model.onnx} (or {@code onnx/model.onnx}, the HF hub
         * layout). Ignored in {@code http} mode, where the classify-service's baked
         * {@code embedders.json} registry owns model resolution.
         */
        private String encoderModelDir = "";

        /**
         * {@code in-jvm} mode only: the checkpoint NAME {@link #encoderModelDir} serves, must
         * equal the bundle manifest's {@code encoder.checkpoint}, or the encoder refuses to embed
         * (a bundle fitted against one encoder scored with another is exactly the silent
         * divergence the parity fixture exists to prevent). In {@code http} mode the same refusal
         * is enforced by the service's manifest plus the response-echo check in
         * {@code HttpConformanceEncoder}.
         */
        private String encoderCheckpoint = "";

        /**
         * {@code in-jvm} mode only: how many encoder forward passes may run concurrently in this JVM
         * ({@code tessary.classifier.conformance.encoder-concurrency}). A transformer forward pass
         * allocates per-call native buffers proportional to sequence length, so an unbounded number
         * of concurrent sweeps sharing the process heap risks an OOM. {@code http} mode gets its
         * backpressure from the classify-service's own in-flight/queue gate instead; this knob bounds
         * only the {@code in-jvm} fallback (default 2).
         */
        private int encoderConcurrency = 2;

        /**
         * Ceiling on how many turns one conformance sweep tick may load and score
         * ({@code tessary.classifier.conformance.max-turns-per-sweep}), the bound on embedding
         * volume that {@link ClassifierProperties#batchSize} does not provide: {@code batch-size}
         * bounds the fresh turns a tick reads past its cursor, but the sweep then re-loads each
         * named conversation whole, so 200 fresh turns spread across 200 long conversations is
         * thousands of turns to embed against a classify-service queue shared with {@code /classify}.
         *
         * <p>The bound applies at a conversation boundary and is soft in one direction: a single
         * conversation longer than the cap is admitted whole rather than split, since splitting it
         * would manufacture violations. Deferred conversations are not skipped; the cursor stops
         * before the first fresh turn of the first deferred conversation, so the next tick picks
         * them up. Zero or negative disables the cap.
         */
        private int maxTurnsPerSweep = 500;

        /**
         * How many consecutive sweeps must fire a conformance finding before automatic Layer-2
         * escalation may spend a triage on it
         * ({@code tessary.classifier.conformance.min-confirmations}).
         *
         * <p>Escalation is costly agentic work, and this detector's measured false alarms are
         * dominated by transient composition wobble: one window whose traffic mix happens to push a
         * rule past its own reference, gone by the next sweep. A recurrence bar counted in
         * activations ({@code triageMinTraceCount}) does not catch that, since a wobble can be wide
         * and still be a wobble; this bar is counted in time instead.
         *
         * <p>2 as the default: the smallest value that is a persistence claim at all, costing one
         * heartbeat of delay rather than hours. 1 restores the pre-guard behaviour;
         * {@code conformance_finding.consecutive_confirmations} is the counter. Scoped to
         * conformance rather than to the shared escalator, since behaviour drift's eligibility must
         * stay bit-identical.
         */
        private int minConfirmations = 2;

        public int getMaxTurnsPerSweep() {
            return maxTurnsPerSweep;
        }

        public void setMaxTurnsPerSweep(int v) {
            this.maxTurnsPerSweep = v;
        }

        public int getMinConfirmations() {
            return minConfirmations;
        }

        public void setMinConfirmations(int v) {
            this.minConfirmations = v;
        }

        public String getEncoderMode() {
            return encoderMode;
        }

        public void setEncoderMode(String v) {
            this.encoderMode = v;
        }

        public String getEncoderModelDir() {
            return encoderModelDir;
        }

        public void setEncoderModelDir(String v) {
            this.encoderModelDir = v;
        }

        public String getEncoderCheckpoint() {
            return encoderCheckpoint;
        }

        public void setEncoderCheckpoint(String v) {
            this.encoderCheckpoint = v;
        }

        public int getEncoderConcurrency() {
            return encoderConcurrency;
        }

        public void setEncoderConcurrency(int v) {
            this.encoderConcurrency = v;
        }
    }

    public Conformance getConformance() {
        return conformance;
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

    public long getDeadLetterCooldownSeconds() {
        return deadLetterCooldownSeconds;
    }

    public void setDeadLetterCooldownSeconds(long v) {
        this.deadLetterCooldownSeconds = v;
    }

    public int getClassifierSampleLimit() {
        return classifierSampleLimit;
    }

    public void setClassifierSampleLimit(int v) {
        this.classifierSampleLimit = v;
    }

    public int getClassifierMinExamples() {
        return classifierMinExamples;
    }

    public void setClassifierMinExamples(int v) {
        this.classifierMinExamples = v;
    }

    public int getThreadCharBudget() {
        return threadCharBudget;
    }

    public void setThreadCharBudget(int v) {
        this.threadCharBudget = v;
    }

    public int getThreadRecentTurns() {
        return threadRecentTurns;
    }

    public void setThreadRecentTurns(int v) {
        this.threadRecentTurns = v;
    }

    public int getThreadMaxObservations() {
        return threadMaxObservations;
    }

    public void setThreadMaxObservations(int v) {
        this.threadMaxObservations = v;
    }

    public long getBehaviorFitIdleIntervalMs() {
        return behaviorFitIdleIntervalMs;
    }

    public long getBehaviorFitSettleDelayMs() {
        return behaviorFitSettleDelayMs;
    }

    public void setBehaviorFitSettleDelayMs(long behaviorFitSettleDelayMs) {
        this.behaviorFitSettleDelayMs = behaviorFitSettleDelayMs;
    }

    public void setBehaviorFitIdleIntervalMs(long v) {
        this.behaviorFitIdleIntervalMs = v;
    }

    public long getTriageIntervalMs() {
        return triageIntervalMs;
    }

    public void setTriageIntervalMs(long v) {
        this.triageIntervalMs = v;
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

    public long getTriageReopenRecurrences() {
        return triageReopenRecurrences;
    }

    public void setTriageReopenRecurrences(long v) {
        this.triageReopenRecurrences = v;
    }

    public int getTriageReopenWindowHours() {
        return triageReopenWindowHours;
    }

    public void setTriageReopenWindowHours(int v) {
        this.triageReopenWindowHours = v;
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
