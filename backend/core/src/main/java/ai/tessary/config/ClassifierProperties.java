// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Defaults live here in code; no yaml entries needed. Strictly off the ingest hot path. */
@ConfigurationProperties(prefix = "tessary.classifier")
public record ClassifierProperties(

        @DefaultValue("200") int batchSize,

        /**
         * Each encoder observation is a model call, so pages are sized to finish well inside the lease; a failure loses
         * at most one page.
         */
        @DefaultValue("32") int encoderBatchSize,

        @DefaultValue("300") long leaseSeconds,

        @DefaultValue("5") int maxAttempts,

        /**
         * A persistently-down backend gets one bounded retry probe per window instead of re-entering the failure loop
         * every heartbeat.
         */
        @DefaultValue("1800") long deadLetterCooldownSeconds,

        /** A cause seen once is not yet worth auditing. */
        @DefaultValue("2") long triageMinTraceCount,

        /** Counted as a run, so an intermittent sidecar never accumulates its way to a trip. */
        @DefaultValue("3") int triageBreakerFailures,

        /** No half-open probe: the cooldown expiring is the probe. */
        @DefaultValue("300") long triageBreakerCooldownSeconds,

        /**
         * A credential gap spends no attempt and trips no breaker: one org with no key must not park everyone's drain.
         */
        @DefaultValue("1800") long triageConfigRetrySeconds,

        /**
         * Blank is a broken deployment, not a degraded mode: without MCP the agent cannot open any evidence, so the
         * engine refuses to run.
         */
        @DefaultValue("") String triageMcpBaseUrl,

        @DefaultValue("e2b") String triageSandbox) {}
