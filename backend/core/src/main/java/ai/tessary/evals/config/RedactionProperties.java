// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the PII redaction guard, bound from {@code evals.redaction.*}. Governs the
 * server-side strip applied on the substrate write path ({@code redaction/RedactionService} →
 * {@code ingest/substrate/SubstrateWriter}) before any trace content is persisted.
 *
 * <p>Default ON: a project's enabled redaction rules (built-in + custom) are applied to every ingested
 * {@code RawEntry} batch before it reaches the substrate spine. The kill switch ({@code enabled=false})
 * turns server-side redaction off entirely if the guard ever misbehaves; client-side SDK
 * redaction and per-rule {@code enabled} toggles still apply independently.
 *
 * <p>Defaults live here in code (no yaml entries needed), mirroring {@link SubstrateProperties} and
 * {@link IngestProperties}.
 */
@Component
@ConfigurationProperties(prefix = "evals.redaction")
public class RedactionProperties {

    /** Master switch for the server-side redaction guard. Default on. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }
}
