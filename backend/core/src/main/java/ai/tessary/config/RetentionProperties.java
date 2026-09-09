// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Platform retention defaults and the sweep's work bounds, bound from {@code tessary.retention.*}.
 * Governs {@code retention/RetentionSweeper}.
 *
 * <p><b>These are DEFAULTS, not the policy.</b> A {@code retention_policy} row for a
 * {@code (project, signal)} overrides the matching value here; the row is the per-customer contract and
 * this is what every project gets without one. A TTL of {@code 0} means keep forever, which is what the
 * whole feature meant until it was enforced.
 *
 * <p>Retention has to be bounded work per pass, not "delete everything older than X" — a first sweep after
 * this shipped could otherwise try to delete months of a busy project's traffic in one statement and hold
 * locks for the duration. {@link #getBatchSize} caps one statement and {@link #getMaxBatchesPerSweep} caps
 * one project-signal per pass, so a large backlog drains over several passes and each pass is short.
 */
@Component
@ConfigurationProperties(prefix = "tessary.retention")
public class RetentionProperties {

    /** Master switch. Off means nothing is ever deleted — the pre-enforcement behaviour. */
    private boolean enabled = true;

    /** How often a sweep runs, in milliseconds. Hourly: the TTLs are in days, so this is oversampled. */
    private long intervalMs = 3_600_000;

    /** Rows deleted per statement. Bounds lock duration, not total work. */
    private int batchSize = 5_000;

    /** Statements per (project, signal) per sweep. Bounds total work, so a backlog drains over passes. */
    private int maxBatchesPerSweep = 20;

    /** Default TTL for ingested traces and everything that cascades from one. 0 = keep forever. */
    private int traceTtlDays = 90;

    /**
     * Default TTL for classifier detections — the six per-classifier tables. Matched to the traces
     * default on purpose: a detection points at a span, and a detection that outlives the span it flagged
     * renders as a row nobody can open.
     */
    private int detectionTtlDays = 90;

    /**
     * Grace window before an unreferenced {@code media_object} is collectable, in hours.
     *
     * <p>Not a TTL: media is collected when nothing references it, at any age. This is the window that
     * makes that safe. Bytes are stored during ingest validation, before the transaction that files their
     * {@code media_ref} rows, so a freshly stored image is legitimately unreferenced for the length of a
     * batch — and a collector with no grace would delete an in-flight batch's images out from under it.
     * A day is far longer than any batch and still bounds how long genuine garbage sits.
     */
    private int mediaGraceHours = 24;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long v) {
        this.intervalMs = v;
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
