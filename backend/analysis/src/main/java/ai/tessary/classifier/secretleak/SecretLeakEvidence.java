// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.secretleak;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire shapes of a secret-leak finding's own detail: what the finding page's "When it leaked"
 * timeline and pins are drawn from. Built off {@code secret_leak_detection}, joined to a finding's
 * facet (its rule and call site) and its bounded witness set, never off {@link
 * ai.tessary.classifier.finding.FindingRow#payloadJson} alone — unlike a tool-error or metric-drift
 * blob, the per-key breakdown and the leak-level detail have no home there.
 */
public final class SecretLeakEvidence {

    private SecretLeakEvidence() {}

    /**
     * Set exactly on a {@code secret_leak} finding: the facet's rule and confidence, how big the leak
     * is, and the two breakdowns the page renders — one masked key at a time, and one leak at a time.
     *
     * @param rule the gitleaks rule id this facet is about, e.g. {@code aws-access-token}
     * @param confidence {@code high} when any leak in the facet's population was HIGH band, else
     *     {@code low}
     * @param leakCount every leaking output since onset, not only the witnesses below
     * @param traceCount the traces those leaks span
     * @param keys one row per masked key the facet has leaked as, newest-leaking-first
     * @param leaks the finding's own witnesses (at most 50, the cap {@code ClassifierArming} already
     *     applied), ordered by time, each joined to what {@code secret_leak_detection} recorded
     */
    public record SecretLeakDetail(
            String rule,
            String confidence,
            long leakCount,
            long traceCount,
            @Nullable String firstAt,
            @Nullable String lastAt,
            List<SecretLeakKeyView> keys,
            List<SecretLeakLeakView> leaks) {}

    /** One masked key's aggregate: how many leaks and traces it accounts for, and whether any instance is unredacted. */
    public record SecretLeakKeyView(
            String masked,
            long leaks,
            long traces,
            @Nullable String lastAt,
            boolean storedRaw) {}

    /** One leaking output: when, which key, whether it is still stored raw, and where to open it. */
    public record SecretLeakLeakView(
            @Nullable String at,
            String masked,
            String stored,
            String traceId,
            @Nullable String spanId) {}
}
