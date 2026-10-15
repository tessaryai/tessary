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

    /** RCA's only analysis path, so {@link #launcherUrl} is effectively required. */
    public record Agentic(

            /** Only "e2b" ships today. */
            @DefaultValue("e2b") String sandbox,

            @DefaultValue("") String launcherUrl,

            @DefaultValue("") String launcherApiKey,

            // No `model` here on purpose: RCA rides the observer's launcher and model (TESSARY_OBSERVER_AGENTIC_MODEL).

            /** Keep below {@code tessary.rca.lease-seconds} or the job is reclaimed mid-run. */
            @DefaultValue("900000") long timeoutMs,

            // Sent as `max_turns`, which forces a text-only reply at the cap rather than a hard kill that discards a
            // partial verdict.
            @DefaultValue("40") int maxTurns,

            /**
             * Blank is a broken deployment: every trace behind the dossier is fetched through this door, so the engine
             * refuses and the report is stamped {@code failed}.
             */
            @DefaultValue("") String mcpBaseUrl) {}
}
