// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.secretleak;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Secret-leak finding detail, built off {@code secret_leak_detection} joined to the finding's facet and witnesses,
 * since the per-key breakdown has no home in the payload.
 */
public final class SecretLeakEvidence {

    /** The facet triple is already unique, so this is a stable marker, not a real measure. */
    public static final String MEASURE = "leak_count";

    private SecretLeakEvidence() {}

    /**
     * @param confidence {@code high} when any leak in the facet was HIGH band @param leakCount every leaking output
     * since onset, not only the witnesses @param leaks the finding's witnesses (at most 50), by time
     */
    public record SecretLeakDetail(
            String rule,
            String confidence,
            long leakCount,
            long traceCount,
            @Nullable String firstAt,
            @Nullable String lastAt,
            List<SecretLeakKeyView> keys,
            List<SecretLeakLeakView> leaks,
            /** What {@link #threshold} counts. */
            String basis,
            long threshold,
            long windowSeconds,
            @Nullable String windowStart,
            @Nullable String windowEnd) {}

    /** One masked key's aggregate: how many leaks and traces it accounts for, and whether any instance is unredacted. */
    public record SecretLeakKeyView(
            String masked,
            long leaks,
            long traces,
            @Nullable String lastAt,
            boolean storedRaw) {}

    /** @param traceId null on the agent-facing view; agents get ids through {@code get_finding_evidence} */
    public record SecretLeakLeakView(
            @Nullable String at,
            String masked,
            String stored,
            @Nullable String traceId,
            @Nullable String spanId) {}
}
