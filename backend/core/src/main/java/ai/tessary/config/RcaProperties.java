// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the RCA worker, bound from {@code tessary.rca.*}. Runs unconditionally: a job exists only when a person
 * pressed "Run RCA".
 */
@ConfigurationProperties(prefix = "tessary.rca")
public record RcaProperties(
        @DefaultValue("5") int batchSize,

        /**
         * Must exceed the agent session's wall clock ({@link Agentic#timeoutMs}), or the worker reclaims a healthy
         * in-flight job.
         */
        @DefaultValue("1200") long leaseSeconds,

        /** A job that keeps hanging or crashing the worker terminates as {@code failed}. */
        @DefaultValue("5") int maxAttempts,

        @DefaultValue Agentic agentic) {

    /**
     * No launcher or model here: RCA runs on the session launcher every agent lane shares ({@code tessary.agentrun.*},
     * falling back to {@code tessary.observer.agentic.*}) and on the observer's model.
     */
    public record Agentic(

            /** Only "e2b" ships today. */
            @DefaultValue("e2b") String sandbox,

            /** Keep below {@code tessary.rca.lease-seconds} or the job is reclaimed mid-run. */
            @DefaultValue("900000") long timeoutMs,

            // Two turns go to the answer, so the session's tool cap is two below this; at the cap the agent answers in
            // text rather than being killed with a partial verdict.
            @DefaultValue("40") int maxTurns,

            /**
             * Blank is a broken deployment: every trace behind the dossier is fetched through this door, so the engine
             * refuses and the report is stamped {@code failed}.
             */
            @DefaultValue("") String mcpBaseUrl) {}
}
