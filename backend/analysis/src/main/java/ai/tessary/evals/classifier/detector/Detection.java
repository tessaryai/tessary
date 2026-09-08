// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.substrate.SubstrateObservation;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of evaluating one {@link BuiltInDetector} against one {@link SubstrateObservation}: a
 * fire (with severity, evidence, and the confidence band it fired at) or a non-fire. The worker
 * materializes a fire into an ONLINE {@code verdict} node ({@code source=automatic}) at the
 * {@code observation} grain.
 *
 * @param severity one of {@link Severity}; {@code null} on a non-fire.
 * @param evidenceJson bounded structured evidence (matched keyword, tool error); {@code null} on a
 *     non-fire. Never the full trace payload.
 * @param confidence the band the detector fired at ({@link Confidence}) — the per-classifier
 *     <em>precision-mode</em> seam: a {@code high}-confidence hit fires in either mode; a
 *     {@code low}-confidence (ambiguous) hit fires only in {@code discovery} mode and is filtered out
 *     of {@code tracking}. The worker persists BOTH bands; the mode gate is applied at read time so the
 *     differing precision/recall is surfaceable per mode from one corpus. {@code null} on a non-fire.
 */
public record Detection(
        boolean fired,
        @Nullable String severity,
        @Nullable String evidenceJson,
        @Nullable String confidence) {

    /** Detector-assigned coarse severity (also the {@code classifier} definition's severity vocabulary). */
    public static final class Severity {
        private Severity() {}

        public static final String INFO = "info";
        public static final String WARN = "warn";
        public static final String CRITICAL = "critical";
    }

    /** The confidence band a detector fired at — the precision-mode seam; also the verdict's confidence. */
    public static final class Confidence {
        private Confidence() {}

        public static final String LOW = "low";
        public static final String HIGH = "high";
    }

    private static final Detection NONE = new Detection(false, null, null, null);

    public static Detection none() {
        return NONE;
    }

    /** A fired detection at the {@link Confidence#HIGH} band (the precise default). */
    public static Detection fired(String severity, String evidenceJson) {
        return new Detection(true, severity, evidenceJson, Confidence.HIGH);
    }

    /** A fired detection at an explicit confidence band ({@link Confidence}). */
    public static Detection fired(String severity, String evidenceJson, String confidence) {
        return new Detection(true, severity, evidenceJson, confidence);
    }
}
