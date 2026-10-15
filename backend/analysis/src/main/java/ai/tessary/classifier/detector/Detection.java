// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;
import org.jspecify.annotations.Nullable;

/**
 * One {@link BuiltInDetector}'s outcome on one {@link SubstrateObservation}; every field is null on a non-fire. @param
 * evidenceJson bounded structured evidence, never the full trace payload @param confidence {@code low} hits fire only
 * in {@code discovery} mode. Both bands are persisted and the mode gate is applied at read time, so each mode's
 * precision is visible from one corpus.
 */
public record Detection(
        boolean fired,
        @Nullable String severity,
        @Nullable String evidenceJson,
        @Nullable String confidence) {

    public static final class Severity {
        private Severity() {}

        public static final String WARN = "warn";
        public static final String CRITICAL = "critical";
    }

    public static final class Confidence {
        private Confidence() {}

        public static final String LOW = "low";
        public static final String HIGH = "high";
    }

    private static final Detection NONE = new Detection(false, null, null, null);

    public static Detection none() {
        return NONE;
    }

    public static Detection fired(String severity, String evidenceJson) {
        return new Detection(true, severity, evidenceJson, Confidence.HIGH);
    }

    public static Detection fired(String severity, String evidenceJson, String confidence) {
        return new Detection(true, severity, evidenceJson, confidence);
    }
}
