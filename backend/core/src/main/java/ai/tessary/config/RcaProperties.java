// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the RCA worker, bound from {@code tessary.rca.*} — mirrors {@link GraderRunProperties}.
 * Runs unconditionally: a job only exists when a person pressed "Run RCA" on a case, so there is no
 * hot-path exposure to gate and no budget beyond the per-press spend ledger.
 */
@ConfigurationProperties(prefix = "tessary.rca")
public record RcaProperties(

        /** Jobs claimed per {@code FOR UPDATE SKIP LOCKED} round. */
        @DefaultValue("5") int batchSize,

        /** Lease duration for a claimed job. Must exceed the analysis stage's wall clock: every RCA runs
         *  a multi-minute agent session ({@link Agentic#timeoutMs}), so the lease is sized to that
         *  plus slack — else the worker reclaims and re-runs a healthy in-flight job. */
        @DefaultValue("1200") long leaseSeconds,

        /** Reclaim count cap: a job that keeps hanging/crashing the worker terminates as {@code failed}. */
        @DefaultValue("5") int maxAttempts,

        /** The sandboxed agent that performs every RCA — mirrors {@code ObserverProperties.Agentic}. */
        @DefaultValue Agentic agentic) {

    /**
     * Config for the RCA engine: an agent session in a fresh E2B microVM gets the project repo
     * cloned at HEAD, the evidence dossier as files, and (optionally) a live MCP connection back to
     * the platform — driven through the same Node launcher sidecar the observer uses. This is RCA's
     * only analysis path, so {@link #launcherUrl} is effectively required.
     */
    public record Agentic(

            /** Which {@code RcaSandbox} runs the agent. Only "e2b" ships today. */
            @DefaultValue("e2b") String sandbox,

            /** Base URL of the Node launcher sidecar that drives the E2B SDK (e.g. http://launcher:8080). */
            @DefaultValue("") String launcherUrl,

            /** Bearer secret the launcher requires. */
            @DefaultValue("") String launcherApiKey,

            // NOTE: there is deliberately no `model` here. RCA rides the observer's launcher sidecar and
            // its bearer key, so it rides the observer's model too — see ObserverProperties.Agentic#model
            // (TESSARY_OBSERVER_AGENTIC_MODEL). One microVM image, one Bedrock inference profile, one knob:
            // a separate TESSARY_RCA_AGENTIC_MODEL only created a second thing to forget to set.

            /** Wall-clock cap on the sandbox run. Keep it below {@code tessary.rca.lease-seconds} or the
             *  job is reclaimed mid-run. */
            @DefaultValue("900000") long timeoutMs,

            // The turn budget for this lane. E2bRcaSandbox threads it into the launcher POST
            // body as `max_turns`, which lane.js forwards into agent-stream.js's `config.agent.build.
            // maxSteps` — the SDK's own documented mechanism for forcing a text-only reply once the cap
            // is hit ("Maximum number of agentic iterations before forcing text-only response"), rather
            // than a hard kill that discards a partial verdict. See ObserverProperties.Agentic#maxTurns
            // (triage's sibling field) for the same note on what was and was not empirically verified.
            @DefaultValue("40") int maxTurns,

            /**
             * Publicly reachable API base the sandbox uses for MCP calls back into the platform (e.g.
             * https://app.tessary.ai), the same value {@code tessary.classifier.triage-mcp-base-url} carries
             * for triage.
             *
             * <p><b>Blank is a broken deployment, not a degraded mode.</b> The dossier carries the
             * finding's claim and the detector's numbers; every trace and span
             * behind them is fetched through this door. A run without it can only paraphrase the detector,
             * so the engine refuses and the report stamps {@code failed}.
             */
            @DefaultValue("") String mcpBaseUrl) {}
}
