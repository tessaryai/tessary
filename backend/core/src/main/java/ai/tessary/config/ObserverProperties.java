// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The {@code tessary.observer.*} prefix is kept deliberately: renaming it would move every deployment's env vars in
 * lockstep for one word. {@link Agentic} is the sandbox launcher triage and RCA share; {@link Encoder} is the
 * groundedness model server. Removed observer keys are simply ignored by Spring.
 */
@Component
@ConfigurationProperties(prefix = "tessary.observer")
public class ObserverProperties {

    /** Must exceed {@code agentic.timeout-ms}, or a reclaimer starts a second run alongside a live one. */
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

    /** Run as its own process so inference cannot starve the web host. No fallback endpoint. */
    public static class Encoder {

        private String url = "";

        private String apiKey = "";

        /**
         * Must not exceed serve.py's {@code --max-inflight}: over it the encoder answers 429; under it sweeps wait here
         * and the encoder never sheds.
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

    public static class Agentic {

        private String launcherUrl = "";

        private String launcherApiKey = "";

        // Must be a Bedrock inference-profile id, not the bare foundation-model name, which 400s. "global." is
        // region-agnostic. Check the account with `aws bedrock list-inference-profiles`.
        private String model = "global.anthropic.claude-sonnet-5-5";
        // A hard kill for a hung run, deliberately not stated in the prompt: a model cannot observe elapsed time. A
        // killed run retries in full, so raise this before lowering maxTurns. BehaviorTriageWorker sizes its lease from
        // it.
        private long timeoutMs = 1_800_000;

        // Forwarded as opencode's maxSteps, which forces a text-only reply at the cap rather than discarding a partial
        // verdict. Two turns go to that landing, so the agent works with 50.
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
