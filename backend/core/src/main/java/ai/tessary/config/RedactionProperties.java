// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the PII redaction guard, bound from {@code tessary.redaction.*}. Governs the
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
@ConfigurationProperties(prefix = "tessary.redaction")
public class RedactionProperties {

    /** Master switch for the server-side redaction guard. Default on. */
    private boolean enabled = true;

    /**
     * Threads used to redact one batch's entries, inside the drain step.
     *
     * <p><b>Measured:</b> on the self-host reference box redaction occupies <b>84% of the single drainer
     * thread</b> at ~500 spans/s (75.4 s of regex in a 90 s run, 196 MB scanned). Once bcrypt left the
     * request path it became almost the whole of the serial stage, so it is the ceiling for a single busy
     * project — which the spool's per-project ordering pins to one drainer by construction. Redacting a
     * batch's entries in parallel is the only way to widen that stage without giving one project several
     * drainers.
     *
     * <p><b>Why a bounded pool rather than the request thread.</b> Redaction ran on the request thread
     * until 2026-07-31, when a thread dump at 100% CPU showed {@code export → enqueue → redactBatch →
     * regex} saturating both vCPUs and stalling senders; it moved to the drain side precisely so that
     * expensive work is bounded. Putting it back would remove that bound, because nothing limits how many
     * requests redact at once under virtual threads. This keeps the work on the drain side and bounded,
     * and only stops it being confined to a single core.
     *
     * <p><b>Measured</b> on the self-host reference box, one project at 1,200 spans/s offered: at 1 it
     * accepted 476 spans/s with 1,193 refusals; at 4 it accepted <b>1,021 spans/s (2.14x)</b> with 174,
     * and p99 moved 5.5 ms to 27 ms — an order of magnitude inside O1's 250 ms. Amdahl on an 84% serial
     * share predicts 2.7x; the gap is fork/join overhead plus the write becoming a larger share of what
     * is left.
     *
     * <p>1 restores the previous behaviour exactly. The default deliberately exceeds the reference box's
     * 2 vCPU, because that is the shape the 2.14x was measured on — regex threads interleave with the
     * write rather than running flat out — but past roughly twice the cores it is contention only.
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
