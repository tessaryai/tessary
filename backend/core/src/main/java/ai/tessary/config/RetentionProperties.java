// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retention defaults and per-pass work bounds; a {@code retention_policy} row overrides these per project. A TTL of
 * {@code 0} keeps forever. {@link #getBatchSize} caps one statement and {@link #getMaxBatchesPerSweep} one
 * project-signal per pass, so a backlog drains over passes.
 */
@Component
@ConfigurationProperties(prefix = "tessary.retention")
public class RetentionProperties {

    /** Off means nothing is ever deleted. */
    private boolean enabled = true;

    /** Bounds lock duration, not total work. */
    private int batchSize = 5_000;

    /** Bounds total work, so a backlog drains over passes. */
    private int maxBatchesPerSweep = 20;

    /** Everything that cascades from a trace. 0 = keep forever. */
    private int traceTtlDays = 90;

    /** Matched to the traces default: a detection that outlives its span renders as a row nobody can open. */
    private int detectionTtlDays = 90;

    /**
     * Not a TTL: bytes are stored before the transaction that files their {@code media_ref} rows, so a fresh image is
     * legitimately unreferenced for a batch's length.
     */
    private int mediaGraceHours = 24;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int v) {
        this.batchSize = v;
    }

    public int getMaxBatchesPerSweep() {
        return maxBatchesPerSweep;
    }

    public void setMaxBatchesPerSweep(int v) {
        this.maxBatchesPerSweep = v;
    }

    public int getTraceTtlDays() {
        return traceTtlDays;
    }

    public void setTraceTtlDays(int v) {
        this.traceTtlDays = v;
    }

    public int getDetectionTtlDays() {
        return detectionTtlDays;
    }

    public void setDetectionTtlDays(int v) {
        this.detectionTtlDays = v;
    }

    public int getMediaGraceHours() {
        return mediaGraceHours;
    }

    public void setMediaGraceHours(int v) {
        this.mediaGraceHours = v;
    }
}
