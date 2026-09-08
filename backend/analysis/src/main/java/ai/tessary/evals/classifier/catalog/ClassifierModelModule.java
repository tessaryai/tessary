// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.catalog;

import ai.tessary.evals.classifier.ClassifierRow;
import ai.tessary.evals.classifier.detector.EncoderScorer;
import ai.tessary.evals.classifier.metric.MetricDriftConfig;
import ai.tessary.evals.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.evals.classifier.substrate.SubstrateReadRepository;
import ai.tessary.evals.plan.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The <b>manifest</b> for one built-in classifier: a single, comprehensive declaration of everything
 * the platform needs to wire it in — its catalog metadata, its default operating point, and how to
 * build its detector from the shared dependencies.
 *
 * <p>Before this, a classifier's facts were scattered across {@link BuiltInClassifierCatalog}'s
 * hand-coded lists, the detector construction, and seeding. A {@code ClassifierModelModule} co-locates
 * them: <b>one module == one classifier, owned in one place.</b> The catalog derives both its
 * built-in list and its detector-dispatch map from the module list, so adding or changing a
 * classifier is a single declaration rather than edits across several files.
 *
 * <p>Fields the manifest carries and the platform reads:
 * <ul>
 *   <li>{@code key/name/description/version} — the catalog metadata ({@link #toBuiltIn()}).
 *   <li>{@code capability} — the flag that decides whether this classifier reaches an org at all.
 *       Mandatory, so a new built-in cannot be added without stating which flag governs it. <b>It is also
 *       the only trust dial.</b> A module used to carry a {@code lifecycle} as well, and seeding read it to
 *       hold an EXPERIMENTAL classifier off by default — two switches expressing one intent. A classifier
 *       whose numbers we do not trust is now simply not enabled outside our own orgs, so every module here
 *       seeds enabled and the decision lives in one place — the org's capability overrides — where it can be
 *       changed without a deploy.
 *   <li>{@code defaultConfigJson} — the per-classifier operating point (e.g. an encoder's
 *       confidence-band thresholds). This is where threshold margin lives — the {@code q8} heads
 *       wobble a few hundredths by batch composition, so a band edge must not sit where scores
 *       cluster or a near-threshold verdict flips between runs.
 *   <li>{@code grain} — WHAT the classifier scores: one observation, one user-facing turn, or a whole
 *       trace (see {@link Grain}). The worker reads this to pick the sweep's candidate query.
 *   <li>{@code detectorFactory} — builds the detector from the shared {@link Deps}. {@code null} for a
 *       classifier that is not observation-grain: the behaviour-drift module implements the
 *       trace-grain {@code TrajectoryDetector} seam instead, and has no {@link BuiltInDetector} to
 *       register in the dispatch map. <b>It is also {@code null} for {@code groundedness}, which IS
 *       observation-grain, for a different reason:</b> that detector class lives in {@code
 *       tessary-paid/groundedness} (#887/#888) and is instead supplied through the {@link
 *       DetectorSupplier} seam, folded into {@link BuiltInClassifierCatalog}'s dispatch map alongside
 *       whatever this list's factories build. A {@code null} here therefore means one of two things
 *       — "not observation/turn grain" or "observation-grain but externally supplied" — and {@code
 *       grainFor} still answers correctly for the second case because grain comes from this manifest's
 *       {@code grain} field, not from whether a factory is present.
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
     * A module that seeds at the high-recall operating point — the catalog default, and correct for a
     * classifier nobody has characterised yet: you cannot narrow a band you have never seen fire.
     *
     * <p>The one that does NOT use it is {@code frustration}, and the reason is that the discovery
     * phase there is over. Measured in production 2026-08-20: discovery surfaced 67.4% of
     * interview-coach turns, 28.5% of zipeats and 20.5% of policy-gpt, against 18.6/14.6/3.5% for the
     * HIGH band alone. A signal that flags two turns in three is not a review queue, and every project
     * seeded that way because the graduation from discovery to tracking was a manual per-signal flag
     * that nothing ever called.
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
     * What one scored unit IS for a classifier — the grain its sweep draws candidates at. The
     * substrate spine is {@code context(session → conversation → turn) → trace → observation}, and a
     * single user-facing turn typically lands as MANY observations (the agent span, its child llm
     * span carrying the same delta, inner planner/summarizer calls, tool spans), so "one observation"
     * and "one thing the user did" are not the same unit.
     *
     * <ul>
     *   <li>{@link #OBSERVATION} — one span. Correct for classifiers whose subject genuinely is a
     *       single call: schema validation, secret leaks, tool errors, output-vs-source groundedness.
     *       An inner LLM call has its own output and can independently be malformed or ungrounded.
     *   <li>{@link #TURN} — one user-facing turn, scored on that turn's ROOT observation. Correct for
     *       conversation-level heads whose subject is what the USER said (frustration): the user
     *       spoke once, so the classifier must fire at most once, or a per-item false-positive rate
     *       multiplies by the span fan-out of the turn.
     *   <li>{@link #TRACE} — one whole trace, dispatched through the {@code ClassifierSweep} seam and
     *       its {@code TrajectoryDetector} port rather than the observation-grain {@link BuiltInDetector}.
     *   <li>{@link #WINDOW} — one window of one bucket. Several families share it — SOP conformance,
     *       tool errors, and the metric-drift classifiers — and the worker routes between them by
     *       looking the detector kind up in {@code ClassifierSweepRegistry}, with no fallthrough.
     * </ul>
     */
    public enum Grain {
        OBSERVATION,
        TURN,
        TRACE,

        /**
         * One WINDOW of one bucket — a stretch of a call site's (or a tool's) own recent traffic,
         * summarized as a distribution and compared against that same bucket's earlier windows.
         *
         * <p><b>This is honest, not a workaround for {@link BuiltInClassifierCatalog#grainFor}
         * returning one value per detector kind.</b> The metric-drift classifiers genuinely have no
         * smaller scored unit. Duration and cost vary enormously for legitimate reasons — a RAG lookup
         * and a thirty-step agent run differ by two orders of magnitude in both — so slow is not bad
         * and expensive is not bad, and there is no per-trace label to assign. What can be judged is
         * whether a population moved against its own past, and a population is a window.
         *
         * <p>The pressure that surfaced it is real all the same: {@code duration_drift} spans a TURN
         * measure and an OBSERVATION measure under one on/off switch, because "this turn was slow" and
         * "34 of its 38 seconds were one {@code search_docs} call" are two halves of one question and
         * therefore one decision a human makes. A single grain per detector kind cannot express that,
         * so each measure's own candidate grain lives in {@link MetricDriftConfig} and the sweep reads
         * it there. What the catalog is asked — "what does this classifier score" — has one true
         * answer, and it is this one.
         *
         * <p><b>A new WINDOW classifier needs a registered {@code ClassifierSweep} before it is declared
         * here.</b> There is no fallthrough any more — an unregistered kind is inert and logs a WARN — and
         * the reason the rule outlives the fallthrough is what happened while there was one.
         * {@code tool_error} landed in the metric-drift arm: its config blob names no {@code measures}, so
         * {@link MetricDriftConfig} fell back to the full default set and it maintained a SECOND copy of
         * every duration and cost baseline and emitted a duplicate finding for every drift the metric
         * classifiers found — under its own classifier id, so turning {@code duration_drift_enabled} off
         * would not have stopped them. Loud-and-inert is a better ending than silent-and-wrong; it is
         * still not a working classifier.
         */
        WINDOW
    }

    /** The shared dependencies a detector is built from — injected once by the catalog. */
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
