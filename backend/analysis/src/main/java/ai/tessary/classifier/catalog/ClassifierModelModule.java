// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.plan.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The manifest for one built-in classifier: catalog metadata, default operating point, and how to
 * build its detector from the shared dependencies. The catalog derives its built-in list and its
 * detector-dispatch map from the module list, so adding or changing a classifier is one declaration.
 *
 * <ul>
 *   <li>{@code key/name/description/version}: the catalog metadata ({@link #toBuiltIn()}).
 *   <li>{@code capability}: the flag that gates this classifier for an org. Mandatory, and the
 *       only trust dial: every module seeds enabled, and whether a classifier actually reaches an
 *       org lives entirely in that org's capability overrides.
 *   <li>{@code defaultConfigJson}: the per-classifier operating point (e.g. an encoder's
 *       confidence-band thresholds). A band edge must not sit where scores cluster, since scores
 *       wobble a few hundredths by batch composition and a near-threshold verdict could flip between runs.
 *   <li>{@code grain}: what the classifier scores, one observation, one user-facing turn, or a whole
 *       trace (see {@link Grain}). The worker reads this to pick the sweep's candidate query.
 *   <li>{@code detectorFactory}: builds the detector from the shared {@link Deps}. {@code null} for
 *       a classifier that is not observation-grain (behaviour-drift implements the trace-grain
 *       {@code TrajectoryDetector} seam instead) or whose detector is supplied externally through the
 *       {@link DetectorSupplier} seam rather than built here. {@code grainFor} still answers
 *       correctly either way, since grain comes from this manifest's {@code grain} field, not from
 *       whether a factory is present.
 * </ul>
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
        String defaultMode) {

    /**
     * A module that seeds at the high-recall operating point: the catalog default, and correct for a
     * classifier nobody has characterised yet: you cannot narrow a band you have never seen fire.
     */
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
     *   <li>{@link #TRACE}: one whole trace, dispatched through the {@code ClassifierSweep} seam and
     *       its {@code TrajectoryDetector} port rather than the observation-grain {@link BuiltInDetector}.
     *   <li>{@link #WINDOW}: one window of one bucket. Several families share it, SOP conformance,
     *       tool errors, and the metric-drift classifiers, and the worker routes between them by
     *       looking the detector kind up in {@code ClassifierSweepRegistry}, with no fallthrough.
     * </ul>
     */
    public enum Grain {
        OBSERVATION,
        TURN,
        TRACE,

        /**
         * One window of one bucket: a stretch of a call site's (or a tool's) own recent traffic,
         * summarized as a distribution and compared against that same bucket's earlier windows. The
         * metric-drift classifiers genuinely have no smaller scored unit: duration and cost vary
         * enormously for legitimate reasons, so there is no per-trace label to assign, only whether a
         * population moved against its own past.
         *
         * <p>A new WINDOW classifier needs a registered {@code ClassifierSweep} before it is declared
         * here: an unregistered kind is inert and logs a WARN rather than falling through to another
         * classifier's baselines.
         */
        WINDOW
    }

    /** The shared dependencies a detector is built from, injected once by the catalog. */
    public record Deps(
            ObjectMapper mapper,
            EncoderScorer encoderScorer,
            SubstrateReadRepository substrate,
            ConversationThreadAssembler threadAssembler) {}

    /** Builds the classifier's detector from the shared dependencies. */
    @FunctionalInterface
    public interface DetectorFactory {
        BuiltInDetector build(Deps deps);
    }

    /** The catalog-metadata view the seeding path consumes. */
    public BuiltInClassifierCatalog.BuiltIn toBuiltIn() {
        return new BuiltInClassifierCatalog.BuiltIn(
                key, name, description, detectorKind, defaultConfigJson, version, capability, defaultMode);
    }
}
