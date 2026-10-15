// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.plan.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The manifest for one built-in classifier; the catalog derives its list and dispatch map from these.
 * {@code capability} is mandatory and the only trust dial. {@code defaultEnabled} is false only for a sweep that spends
 * the org's own credit, and never applies to a seeded row. A band edge in {@code defaultConfigJson} must not sit where
 * scores cluster, since scores wobble by batch. {@code detectorFactory} is null for window-grain or externally supplied
 * detectors.
 */
public record ClassifierModelModule(
        String key,
        String name,
        String description,
        String detectorKind,
        int version,
        Capability capability,
        Grain grain,
        @Nullable String defaultConfigJson,
        @Nullable DetectorFactory detectorFactory,
        String defaultMode,
        boolean defaultEnabled) {

    public ClassifierModelModule(
            String key,
            String name,
            String description,
            String detectorKind,
            int version,
            Capability capability,
            Grain grain,
            @Nullable String defaultConfigJson,
            @Nullable DetectorFactory detectorFactory,
            String defaultMode) {
        this(
                key,
                name,
                description,
                detectorKind,
                version,
                capability,
                grain,
                defaultConfigJson,
                detectorFactory,
                defaultMode,
                true);
    }

    /** The high-recall default: you cannot narrow a band you have never seen fire. */
    public ClassifierModelModule(
            String key,
            String name,
            String description,
            String detectorKind,
            int version,
            Capability capability,
            Grain grain,
            @Nullable String defaultConfigJson,
            @Nullable DetectorFactory detectorFactory) {
        this(
                key,
                name,
                description,
                detectorKind,
                version,
                capability,
                grain,
                defaultConfigJson,
                detectorFactory,
                ClassifierRow.Mode.DISCOVERY);
    }

    /**
     * What one scored unit is for a classifier: the grain its sweep draws candidates at. A single
     * user-facing turn typically lands as many observations (the agent span, its child llm span,
     * inner planner/summarizer calls, tool spans), so "one observation" and "one thing the user did"
     * are not the same unit.
     *
     * <ul>
     *   <li>{@link #OBSERVATION}: one span. Correct for classifiers whose subject genuinely is a
     *       single call: schema validation, secret leaks, tool errors, output-vs-source groundedness.
     *   <li>{@link #TURN}: one user-facing turn, scored on that turn's root observation. Correct for
     *       conversation-level heads whose subject is what the user said (frustration): the user spoke
     *       once, so the classifier must fire at most once.
     *   <li>{@link #WINDOW}: one window of one bucket. Several families share it, tool errors and
     *       the metric-drift classifiers, and the worker routes between them by
     *       looking the detector kind up in {@code ClassifierSweepRegistry}, with no fallthrough.
     * </ul>
     */
    public enum Grain {
        OBSERVATION,
        TURN,

        /**
         * One window of one bucket compared against its own earlier windows. A new WINDOW classifier needs a registered
         * {@code ClassifierSweep} first, or it is inert.
         */
        WINDOW
    }

    public record Deps(ObjectMapper mapper, EncoderScorer encoderScorer, SubstrateReadRepository substrate) {}

    @FunctionalInterface
    public interface DetectorFactory {
        BuiltInDetector build(Deps deps);
    }

    public BuiltInClassifierCatalog.BuiltIn toBuiltIn() {
        return new BuiltInClassifierCatalog.BuiltIn(
                key,
                name,
                description,
                detectorKind,
                defaultConfigJson,
                version,
                capability,
                defaultMode,
                defaultEnabled);
    }
}
