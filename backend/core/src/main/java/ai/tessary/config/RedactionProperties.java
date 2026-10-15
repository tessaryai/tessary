// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-side redaction on the substrate write path, before any trace content is persisted. Default on;
 * {@code enabled=false} is a kill switch, and client-side and per-rule toggles still apply.
 */
@ConfigurationProperties(prefix = "tessary.redaction")
public record RedactionProperties(

        @DefaultValue("true") boolean enabled,

        /**
         * Threads redacting one batch inside the drain step. Redaction was 84% of the single drainer, and per-project
         * ordering pins a project to one drainer, so parallelism is the only way to widen it; it must not move back to
         * request threads, which are unbounded under virtual threads. Measured at 4: 2.14x accepted throughput, p99 27
         * ms. 1 restores serial behaviour; past ~2x cores is contention only.
         */
        @DefaultValue("4") int parallelism) {

    public RedactionProperties {
        if (parallelism < 1) {
            throw new IllegalArgumentException("tessary.redaction.parallelism must be at least 1, not " + parallelism);
        }
    }
}
