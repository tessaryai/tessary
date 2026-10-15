// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Server-side redaction on the substrate write path, before any trace content is persisted. Default on;
 * {@code enabled=false} is a kill switch, and client-side and per-rule toggles still apply.
 */
@Component
@ConfigurationProperties(prefix = "tessary.redaction")
public class RedactionProperties {

    private boolean enabled = true;

    /**
     * Threads redacting one batch inside the drain step. Redaction was 84% of the single drainer, and per-project
     * ordering pins a project to one drainer, so parallelism is the only way to widen it; it must not move back to
     * request threads, which are unbounded under virtual threads. Measured at 4: 2.14x accepted throughput, p99 27 ms.
     * 1 restores serial behaviour; past ~2x cores is contention only.
     */
    private int parallelism = 4;

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int v) {
        if (v < 1) {
            throw new IllegalArgumentException("tessary.redaction.parallelism must be at least 1, not " + v);
        }
        this.parallelism = v;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }
}
