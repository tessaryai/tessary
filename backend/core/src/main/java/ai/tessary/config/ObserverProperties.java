// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The agentic-sandbox and encoder configuration, bound from {@code tessary.observer.*}.
 *
 * <p><b>The prefix is a historical name and is kept deliberately.</b> This class configured the git
 * observer, which has since been removed. What survives are its two sub-blocks, and they never belonged to the
 * observer alone: {@link Agentic} is the sandbox launcher every agentic run goes through — Layer-2
 * triage and agentic RCA both bind it — and {@link Encoder} is the groundedness model server the
 * metric detectors call. Renaming {@code tessary.observer.*} would mean
 * moving every deployment's env vars in lockstep with a release, for a rename that buys a reader one
 * word; the honest fix is a broader config pass, not this one. Note {@code docker-compose.yml} already
 * falls {@code TESSARY_RCA_AGENTIC_LAUNCHER_API_KEY} back to the observer-named key for the same reason.
 *
 * <p>The observer-only knobs are gone: the batch cron and its zone, the claim-batch and attempt bounds,
 * the compare-window cap, the {@code .tessary/} directory, the change-request switch, the analyzer
 * selector, the ignore globs, and the agentic block's drift-analysis sandbox selector and remediate
 * switch. A deployment that still sets one of those env vars is not failed —
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
     * {@code tessary.observer.encoder.*}: the groundedness model server,
     * {@code classifiers/groundedness/serve.py}, run as its own process so inference cannot starve
     * the web host. Required for signal sweeps — there is no fallback endpoint.
     */
    public static class Encoder {

        /** Base URL of the groundedness model server (e.g. http://classify.tessary.internal:8080). */
        private String url = "";

        /** Bearer secret the groundedness model server requires. */
        private String apiKey = "";

        /**
         * How many {@code /classify} requests this backend has in flight at once, across every sweep
         * and every head. Must not exceed the encoder's own ceiling (serve.py's
         * {@code --max-inflight}, env {@code MAX_INFLIGHT}, default 1): over it the
         * encoder queues and then answers 429, under it sweeps wait here, in-process, and the
         * encoder never sees a burst it has to shed.
         */
        private int maxInflight = 2;

        public int getMaxInflight() {
            return maxInflight;
        }

        public void setMaxInflight(int v) {
            this.maxInflight = v;
        }

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
     * The sandbox launcher the Layer-2 triage and agentic RCA lanes run through, bound from
     * {@code tessary.observer.agentic.*}.
     */
    public static class Agentic {

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
        // The TRIAGE lane's wall clock: a hard kill for a run that has hung, NOT a budget the agent
        // plans against. It is deliberately not stated in the prompt — a model cannot observe elapsed
        // time, so a number it cannot measure only invites it to guess and cut its reading short. The
        // turn cap below is the budget it is told about, and this is sized to be slack around it: 30
        // minutes against maxTurns' 50 working turns. A run killed here records no ruling and retries,
        // which costs a whole second run, so raise this before lowering maxTurns if live runs come
        // near it. BehaviorTriageWorker.leaseSeconds() sizes the job lease off this value, so the
        // lease follows it up on its own.
        private long timeoutMs = 1_800_000;

        // The turn budget for the TRIAGE lane only. E2bTriageSandbox threads it into the launcher POST
        // body as `max_turns`, which triage.js forwards into agent-stream.js's
        // `config.agent.build.maxSteps` — the SDK's own
        // documented mechanism for forcing a text-only reply once the cap is hit (see
        // @opencode-ai/sdk's AgentConfig.maxSteps: "Maximum number of agentic iterations before
        // forcing text-only response"), rather than a hard kill that discards a partial verdict.
        // NOT independently verified against a live run in the change that introduced this field —
        // see that change's PR description for what was and was not empirically confirmed.
        //
        // This is the RAW cap; two of it go to opencode's text-only landing, so the number the prompt
        // states, and the number the agent actually works with, is 50.
        private int maxTurns = 52;

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
}
