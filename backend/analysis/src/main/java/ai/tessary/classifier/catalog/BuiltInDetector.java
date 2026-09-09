// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.EncoderDetector;
import ai.tessary.classifier.detector.MalformedOutputDetector;
import ai.tessary.classifier.detector.RegexDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.pipeline.CallSiteFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A built-in signal detector: the per-classifier evaluation seam the {@link ClassifierWorker}
 * dispatches on. Deterministic detectors (pattern + tool-error + structural) evaluate per
 * observation with no model call; the encoder tier ({@link EncoderDetector}) scores text against
 * a shared ONNX head and overrides {@link #detectBatch} to amortize one serving call per sweep
 * batch. Classifier- and regex-backed user signals plug in behind the same seam.
 */
public interface BuiltInDetector {

    /** The {@code signal.detector} value this implementation handles. */
    String kind();

    /**
     * Evaluate one observation. {@code config} is the classifier's per-project {@code config_json}
     * (may be null, meaning the detector's baked defaults). Pure and side-effect-free: the worker
     * owns persistence.
     */
    Detection detect(SubstrateObservation obs, @Nullable String config);

    /**
     * Evaluate a sweep batch, one {@link Detection} per observation, index-aligned. The default
     * loops {@link #detect}; a detector whose evaluation has per-call overhead (the encoder tier's
     * HTTP scoring call) overrides this to batch it.
     */
    default List<Detection> detectBatch(List<SubstrateObservation> batch, @Nullable String config) {
        List<Detection> out = new ArrayList<>(batch.size());
        for (SubstrateObservation obs : batch) out.add(detect(obs, config));
        return out;
    }

    /**
     * The call-site facts this detector gates on: the code-derived columns whose absence makes it
     * abstain rather than score. Empty (the default) for a detector that reads only the
     * observation. Declaring a fact here matters beyond the next batch: {@link
     * ClassifierService#rewindForCallSiteFact} rewinds exactly the signals that declare a changed
     * fact, so a detector that reads a call-site column without declaring it here would silently
     * strand its history when that fact changes.
     */
    default Set<CallSiteFact> callSiteFactsRead() {
        return Set.of();
    }

    /** The canonical {@code signal.detector} values: the dispatch keys for the built-in catalog. */
    final class Kind {
        private Kind() {}

        public static final String FRUSTRATION = "frustration";

        /** The curated credential-pattern detector behind the Secret Leak built-in. */
        public static final String SECRET_LEAK = "secret_leak";

        /**
         * Output-vs-declared-schema validation behind the Malformed Output built-in
         * ({@link MalformedOutputDetector}): the call site's captured {@code output_schema}
         * validated over the observation's output, deterministically.
         */
        public static final String MALFORMED_OUTPUT = "malformed_output";

        /**
         * Output-vs-source correctness behind the Groundedness built-in: a three-way NLI-style
         * entailment head scores the output's support against the observation's input text, gated
         * to call sites whose declared {@code shape} carries verifiable source content. Its {@link
         * BuiltInDetector} reaches the dispatch map through the {@link DetectorSupplier} seam
         * rather than a {@code detectorFactory} closure; see {@link BuiltInClassifierCatalog}'s
         * manifest entry for this key.
         */
        public static final String GROUNDEDNESS = "groundedness";

        /**
         * Regex/keyword detection: an NL phrase is compiled once to a {@link java.util.regex.Pattern}
         * at definition time and matched literally over observation text at evaluation time, with
         * no model call. Handled by {@link RegexDetector}.
         */
        public static final String REGEX = "regex";

        /**
         * Unsupervised, self-fitting drift detection over a trace's action skeleton: is this agent
         * doing something it does not usually do? This kind is trace-grain and stateful, so it
         * implements {@code TrajectoryDetector} rather than this interface and is dispatched
         * through the {@code ClassifierSweep} seam, not {@link #detectBatch}. It ships as a fitting
         * procedure that learns each project's normal from that project's own traces; "atypical for
         * this agent" has no transferable model to ship.
         */
        public static final String BEHAVIOR_DRIFT = "behavior_drift";

        /**
         * Windowed distribution drift on the two duration measures: has this call site's turns, or
         * one of its tools, moved away from their own recent past? Like {@link #BEHAVIOR_DRIFT} it
         * ships as a per-project fitting procedure and carries no {@link BuiltInDetector}; it is
         * dispatched through the {@code ClassifierSweep} registered on {@link
         * ClassifierModelModule.Grain#WINDOW}. It labels nothing: slow is not bad, so the scored
         * unit is a window of a bucket compared against that bucket's own earlier windows.
         */
        public static final String DURATION_DRIFT = "duration_drift";

        /**
         * Windowed distribution drift on spend: has this call site's cost per turn moved away from
         * its own recent past? Same structure as {@link #DURATION_DRIFT}: a per-project fitting
         * procedure, no {@link BuiltInDetector}, dispatched through its own {@code ClassifierSweep}
         * on {@link ClassifierModelModule.Grain#WINDOW}, and it labels no trace.
         *
         * <p>One measure opens a finding here, not five: {@code cost} is the headline, and the four
         * token buckets (input, output, cache read, cache write) are summarized every window as the
         * decomposition attached to that finding.
         *
         * <p>Rates are resolved at sweep time against the current price book. A model with no
         * published rate is unpriced, not free, so its turn leaves the distribution rather than
         * joining it at $0; since the sweep's cursor moves forward only, traffic abstained on stays
         * unscoreable.
         */
        public static final String COST_DRIFT = "cost_drift";

        /**
         * The {@code tool_error} classifier. Like the two above it this is a dispatch key with no
         * {@link BuiltInDetector} and no sweep behind it: its rate is recomputed from an hourly
         * aggregate on every read rather than accumulated, so the catalog entry exists to make the
         * classifier listable, flag-gated and switchable, not to route a job to a worker.
         */
        public static final String TOOL_ERROR = "tool_error";

        /**
         * SOP-conformance / behavior-drift over an authored rulebook: for every rule, on every
         * turn, did the rule apply and did the agent satisfy it, and per rule over the population,
         * did the compliance rate fall below what the reference period predicts? Like {@link
         * #BEHAVIOR_DRIFT} it carries no {@link BuiltInDetector}; its unit of judgement is a window
         * of a rule's admitted activations, scored against an exported artifact bundle by whichever
         * {@code ClassifierSweep} claims this kind. It is withheld from every org by its capability
         * flag ({@code sop_conformance_enabled}), and its {@code measures: []} keeps the
         * metric-drift fold inert regardless.
         *
         * <p>The implementing class is deliberately not named here, and neither are the others
         * above: this interface is the seam, and naming an implementation in prose is a reference
         * nothing checks, so a rename or move can leave it silently pointing at nothing.
         */
        public static final String SOP_CONFORMANCE = "sop_conformance";

        /**
         * Defined-but-inert: the signal is seeded and listable but never produces events. Retained
         * for any future placeholder built-in.
         */
        public static final String INERT = "inert";

        /**
         * The kinds that cannot run without the standalone classify-service, because their score
         * comes from a model resident in it rather than from anything this process can compute.
         * Kept as one set because which debug family a classifier belongs to and whether the
         * encoder deployment can be scaled to zero both need the same answer.
         *
         * <p>{@link #SOP_CONFORMANCE} is deliberately not here despite its default
         * {@code encoder-mode=http} reaching the service's {@code /embed}: it needs the service
         * only when an enabled project has a head-carrying bundle deployed, and its capability flag
         * is off for everyone. Revisit this membership when that flag first turns on.
         */
        public static final Set<String> ENCODER_BACKED = Set.of(FRUSTRATION, GROUNDEDNESS);
    }
}
