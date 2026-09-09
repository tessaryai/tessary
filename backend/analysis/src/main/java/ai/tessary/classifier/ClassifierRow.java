// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.worker.ClassifierWorker;
import org.jspecify.annotations.Nullable;

/**
 * A project-scoped classifier <em>definition</em> — a behavior detector over the streaming
 * substrate, evaluated async by {@link ClassifierWorker}. Built-ins are seeded per project by
 * {@link BuiltInClassifierCatalog}; {@code classifierKey} is unique per project so a built-in is
 * enabled/disabled and versioned independently per tenant.
 *
 * @param detector the {@link BuiltInDetector.Kind} the worker dispatches on; {@code inert} means
 *     defined-but-not-evaluated.
 * @param configJson detector parameters (e.g. keyword lists), opaque to the schema; {@code null}
 *     falls back to the catalog defaults baked into the detector.
 * @param mode the operating point ({@link Mode}): {@code discovery} (high recall, the default) or
 *     {@code tracking} (high precision). Tenant-controlled like {@link #enabled}; never bumps
 *     {@link #version} and is never clobbered by a built-in catalog re-seed.
 */
public record ClassifierRow(
        String id,
        String projectId,
        String classifierKey,
        String name,
        @Nullable String description,
        String detector,
        @Nullable String configJson,
        boolean builtIn,
        int version,
        boolean enabled,
        String mode,
        String createdAt,
        String updatedAt) {

    /**
     * The classifier's operating point — the discovery-vs-tracking precision modes. {@code discovery}
     * tolerates false positives for high recall (hits are human-reviewed in clusters); {@code tracking}
     * keeps high precision because it feeds metrics/alerts. One definition, two operating points.
     */
    public static final class Mode {
        private Mode() {}

        public static final String DISCOVERY = "discovery";
        public static final String TRACKING = "tracking";
    }
}
