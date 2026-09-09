// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The agentic-sandbox and encoder configuration, bound from {@code tessary.observer.*}.
 *
 * <p><b>The prefix is a historical name and is kept deliberately.</b> This class configured the git
 * observer, which Track A removed. What survives are its two sub-blocks, and they never belonged to the
 * observer alone: {@link Agentic} is the sandbox launcher every agentic run goes through — Layer-2
 * triage and agentic RCA both bind it — and {@link Encoder} is the classify-service endpoint the
 * conformance encoder and the metric detectors call. Renaming {@code tessary.observer.*} would mean
 * moving every deployment's env vars in lockstep with a release, for a rename that buys a reader one
 * word; the honest fix is the epic-7 config pass, not this one. Note {@code docker-compose.yml} already
 * falls {@code TESSARY_RCA_AGENTIC_LAUNCHER_API_KEY} back to the observer-named key for the same reason.
 *
 * <p>The observer-only knobs are gone: the batch cron and its zone, the claim-batch and attempt bounds,
 * the compare-window cap, the {@code .tessary/} directory, the change-request switch, the analyzer
 * selector and the ignore globs. A deployment that still sets one of those env vars is not failed —
 * Spring ignores an unbound key — it simply has no effect. {@link #getLeaseSeconds()} stays: the
 * Layer-2 triage worker leases its own agentic jobs on it.
 */
@Component
@ConfigurationProperties(prefix = "tessary.observer")
public class ObserverProperties {

    /**
     * How long a claimed agentic job's lease runs before a reclaimer may retry it. Must exceed
     * {@code agentic.timeout-ms}: a sandboxed run holds its claim for many minutes, and a shorter lease
     * would let the reclaimer start a second one alongside it. Read by the Layer-2 triage worker.
     */
    private long leaseSeconds = 1_800;

    private final Agentic agentic = new Agentic();
    private final Encoder encoder = new Encoder();

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long v) {
        this.leaseSeconds = v;
    }

    public Agentic getAgentic() {
        return agentic;
    }

    public Encoder getEncoder() {
        return encoder;
    }

    /**
     * Where encoder classification ({@code POST /classify}) is served, bound from
     * {@code tessary.observer.encoder.*}: the standalone classify-service, running on ECS
     * Fargate in production (so CPU inference cannot starve the web host) and as the
     * {@code classify} container in docker-compose.dev.yml locally. Required for signal
     * sweeps — there is no fallback endpoint.
     */
    public static class Encoder {

        /** Base URL of the classify service (e.g. http://classify.tessary.internal:8080). */
        private String url = "";

        /** Bearer secret the classify service requires. */
        private String apiKey = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String v) {
            this.url = v;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String v) {
            this.apiKey = v;
        }
    }

    /**
     * Tuning for the agentic analyzer ({@code analyzer-type=agentic}), bound from
     * {@code tessary.observer.agentic.*}. Only consulted when that analyzer is active.
     */
    public static class Agentic {

        /** Which {@code AnalysisSandbox} runs the agent. Only "e2b" ships today. */
        private String sandbox = "e2b";

        /** Base URL of the Node launcher sidecar that drives the E2B SDK (e.g. http://launcher:8080). */
        private String launcherUrl = "";

        /** Bearer secret the launcher requires. */
        private String launcherApiKey = "";

        // Passed to the sandbox agent's --model, and the fallback for every AGENT_VM lane on a project
        // with no seeded rows (RCA and triage share it; grader generation has tessary.synth.agentic-model).
        // The launcher qualifies this with a provider and hands it to Bedrock verbatim, so it must be a
        // Bedrock *inference-profile* id and NOT the bare foundation-model name — the bare name 400s.
        // "global." is region-agnostic; "us.anthropic.claude-sonnet-5" pins a geo. Confirm what is ACTIVE
        // in the account with `aws bedrock list-inference-profiles`.
        private String model = "global.anthropic.claude-sonnet-5";
        // When true, the agent edits/adds graders via the evals plugin and the backend opens a
        // draft PR with the changes. Default false = detect-only (alert + proposals, no PR).
        private boolean remediate = false;
        // The agent runs with NO turn cap (it was getting cut off mid-analysis); the
        // wall-clock is the only bound. Remediation (per-grader author+validate via the
        // plugin) legitimately takes many minutes, so this is generous. Keep it below
        // ObserverProperties.leaseSeconds (else the job is reclaimed mid-run).
        private long timeoutMs = 1_200_000;

        // B (#994): the turn budget for the TRIAGE lane only — E2bAnalysisSandbox (drift analysis /
        // remediation, driven by analyze.js) reads this same Agentic block for launcherUrl/timeoutMs
        // but deliberately does NOT read this field, so the "no turn cap" comment above still holds
        // for it. E2bTriageSandbox threads it into the launcher POST body as `max_turns`, which
        // triage.js forwards into agent-stream.js's `config.agent.build.maxSteps` — the SDK's own
        // documented mechanism for forcing a text-only reply once the cap is hit (see
        // @opencode-ai/sdk's AgentConfig.maxSteps: "Maximum number of agentic iterations before
        // forcing text-only response"), rather than a hard kill that discards a partial verdict.
        // NOT independently verified against a live run in the change that introduced this field —
        // see that change's PR description for what was and was not empirically confirmed.
        private int maxTurns = 40;

        // F4 (#994): the per-run spend cap on TRIAGE — ModelLane.TRIAGE's javadoc used to say
        // "deliberately uncapped at launch (launch decision D6)"; this is D6 landing, on the meter
        // F1-F3 made honest first. POST-HOC, not preventive: E2bTriageSandbox checks the run's ACTUAL
        // priced cost against this AFTER the run completes and its usage is already booked (there is
        // no live per-turn cost signal to intervene on mid-run — see B's maxTurns note on the seam this
        // shares). A run over the cap is FLAGGED (a structured OPS log line + a span attribute an
        // operator can alert on), not rejected: the money is already spent either way, and discarding
        // an otherwise-valid ruling after paying for it protects nothing — it only throws away the
        // ruling on top of the spend. $3.00 is a starting product default (roughly the pre-#994
        // unoptimized single-run cost the issue measured), not a value anyone has tuned against real
        // TRIAGE traffic yet; adjust it once real numbers exist.
        private java.math.BigDecimal maxCostUsd = new java.math.BigDecimal("3.00");

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

        public String getModel() {
            return model;
        }

        public void setModel(String v) {
            this.model = v;
        }

        public boolean isRemediate() {
            return remediate;
        }

        public void setRemediate(boolean v) {
            this.remediate = v;
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

        public java.math.BigDecimal getMaxCostUsd() {
            return maxCostUsd;
        }

        public void setMaxCostUsd(java.math.BigDecimal v) {
            this.maxCostUsd = v;
        }
    }
}
