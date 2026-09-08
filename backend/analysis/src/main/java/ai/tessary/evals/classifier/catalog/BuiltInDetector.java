// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.catalog;

import ai.tessary.evals.classifier.ClassifierService;
import ai.tessary.evals.classifier.detector.Detection;
import ai.tessary.evals.classifier.detector.EncoderDetector;
import ai.tessary.evals.classifier.detector.MalformedOutputDetector;
import ai.tessary.evals.classifier.detector.RegexDetector;
import ai.tessary.evals.classifier.substrate.SubstrateObservation;
import ai.tessary.evals.classifier.worker.ClassifierWorker;
import ai.tessary.evals.pipeline.CallSiteFact;
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
     * Evaluate one observation. {@code config} is the classifier's per-project {@code config_json} (may be
     * null → the detector's baked defaults). Pure + side-effect-free: the worker owns persistence.
     */
    Detection detect(SubstrateObservation obs, @Nullable String config);

    /**
     * Evaluate a sweep batch, one {@link Detection} per observation, index-aligned. The default
     * simply loops {@link #detect}; a detector whose evaluation has per-call overhead (the encoder
     * tier's HTTP scoring call) overrides this to batch it.
     */
    default List<Detection> detectBatch(List<SubstrateObservation> batch, @Nullable String config) {
        List<Detection> out = new ArrayList<>(batch.size());
        for (SubstrateObservation obs : batch) out.add(detect(obs, config));
        return out;
    }

    /**
     * The call-site facts this detector GATES on — the code-derived columns whose absence makes it
     * abstain rather than score. Empty (the default) for every detector that reads only the
     * observation: those depend on nothing but the trace, so nothing outside the trace can invalidate
     * their sweep.
     *
     * <p>Declaring one is a statement about <b>history</b>, not just about the next batch. These facts
     * are captured from the repo during agentic synthesis, which the plugin will not run until the
     * platform has ingested correctly-tagged traces — so a project's earliest observations are always
     * swept before the fact exists, scored {@code none()}, and left behind a cursor that only moves
     * forward. {@link ClassifierService#rewindForCallSiteFact} rewinds exactly the signals that
     * declare a changed fact here, which is the whole reason this method exists: a detector that
     * silently reads a call-site column without declaring it would keep the stranded-history bug.
     */
    default Set<CallSiteFact> callSiteFactsRead() {
        return Set.of();
    }

    /** The canonical {@code signal.detector} values — the dispatch keys for the built-in catalog. */
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
         * entailment head scores the output's support against the observation's input text, gated to
         * call sites whose declared {@code shape} carries verifiable source content. Unlike every other
         * kind above, its {@link BuiltInDetector} is not built from this file's manifest: the class is
         * paid ({@code tessary-paid/groundedness}) and reaches the dispatch map through the {@link
         * DetectorSupplier} seam, not a {@code detectorFactory} closure. See {@link
         * BuiltInClassifierCatalog}'s manifest entry for this key.
         */
        public static final String GROUNDEDNESS = "groundedness";

        /**
         * Regex/keyword detection: an NL phrase is compiled once to a {@link java.util.regex.Pattern}
         * at definition time and matched literally over observation text at evaluation time, with no model
         * call. Handled by {@link RegexDetector}.
         */
        public static final String REGEX = "regex";

        /**
         * Unsupervised, self-fitting drift detection over a trace's ACTION SKELETON: is this agent
         * doing something it does not usually do? Unlike every other kind here this one is
         * <b>trace-grain</b> and stateful, so it implements {@code TrajectoryDetector} rather than
         * this interface and is dispatched through the {@code ClassifierSweep} seam registered for
         * this kind, not by {@link #detectBatch}. "Atypical for this agent" is definitionally project- and
         * time-relative, so it ships as a fitting procedure that learns each project's normal from
         * that project's own traces — there is no transferable model to ship.
         */
        public static final String BEHAVIOR_DRIFT = "behavior_drift";

        /**
         * Windowed distribution drift on the two duration measures — has this call site's turns, or one
         * of its tools, moved away from their own recent past? Like {@link #BEHAVIOR_DRIFT} it ships as a
         * per-project fitting procedure rather than a model and carries no {@link BuiltInDetector}: it is
         * dispatched through the {@code ClassifierSweep} registered for this kind on
         * {@link ClassifierModelModule.Grain#WINDOW}.
         *
         * <p>Unlike every other kind here it labels nothing. Slow is not bad — a forty-second research
         * run and a two-second lookup are both routinely correct — so there is no per-trace verdict to
         * write, and the scored unit is a WINDOW of a bucket compared against that bucket's own earlier
         * windows.
         */
        public static final String DURATION_DRIFT = "duration_drift";

        /**
         * Windowed distribution drift on spend — has this call site's cost per turn moved away from its
         * own recent past? The sibling of {@link #DURATION_DRIFT} in every structural respect: a
         * per-project fitting procedure rather than a model, no {@link BuiltInDetector}, dispatched
         * through its own {@code ClassifierSweep} on {@link ClassifierModelModule.Grain#WINDOW}, and
         * labelling no trace,
         * because a $0.40 turn is as routinely correct as a forty-second one.
         *
         * <p><b>One measure opens a finding here, not five.</b> {@code cost} is the headline; the four
         * token buckets — input, output, cache read, cache write — are summarized every window and
         * attached to that finding as the decomposition that explains it. A prompt edit that stops the
         * cache hitting moves cost, input tokens and cache reads at once, and it is one cause, so it is
         * one row (PROGRAM.md §6.1).
         *
         * <p>Its distinctive hazard is the price book rather than the clock: a model carrying no published
         * rate is <b>unpriced, not free</b>, so its turn leaves the distribution rather than joining it at
         * $0. Rates are therefore resolved at SWEEP time against the current book, which keeps a price-book
         * gap only as long as the deploy that closes it — the sweep's keyset cursor moves forward only, so
         * traffic abstained on is unscoreable forever (PROGRAM.md §3.3).
         */
        public static final String COST_DRIFT = "cost_drift";

        /**
         * The {@code tool_error} classifier. Like the two above it this is a dispatch key with no
         * {@link BuiltInDetector} behind it, and unlike them it has no sweep behind it either: the rate is
         * recomputed from an hourly aggregate on every read rather than accumulated
         * ({@code classifiers/tool_error/PROGRAM.md} §5), so the catalog entry exists to make the
         * classifier listable, flag-gated and switchable, not to route a job to a worker.
         *
         * <p>This key was live once and migration {@code 0030} deleted it outright, because that version
         * wrote a detection per failing observation and every detection enqueued a grader run. Nothing
         * here does: the rebuilt classifier writes at most one finding per tool.
         */
        public static final String TOOL_ERROR = "tool_error";

        /**
         * SOP-conformance / behaviour-drift over an AUTHORED rulebook — for every rule, on every
         * turn, did the rule apply and did the agent satisfy it, and per rule over the population,
         * did the compliance rate fall below what the reference period predicts for this traffic?
         * Like {@link #BEHAVIOR_DRIFT} it carries no {@link BuiltInDetector}: its unit of judgement
         * is a WINDOW of a rule's admitted activations, scored against an exported artifact bundle by
         * whichever {@code ClassifierSweep} claims this kind. The catalog entry seeds ENABLED like every
         * built-in and is withheld from every org by its capability flag
         * ({@code sop_conformance_enabled}, targeted on for nobody yet), and its {@code measures: []}
         * keeps the metric-drift fold inert as belt-and-braces should the routing ever regress.
         *
         * <p>The implementing class is deliberately NOT named here, and neither are the others above.
         * This interface is the SEAM; naming its implementations in prose used to put two
         * fully-qualified conformance package names in this file, which no compiler and no ArchUnit rule
         * could see.
         *
         * <p>The previous version of this note claimed {@code scripts/check-open-boundary.sh} rule 1
         * would catch that, and #841 proved the claim false in the only way that matters: rule 1 greps
         * the open tree for the packages the OVERLAY holds, so it arms on {@code ai.tessary.paid.…} and
         * the strings here named {@code ai.tessary.evals.…}. They would have survived the move
         * invisibly, pointing at a package that no longer exists, and the build would have stayed green.
         * A prose reference written as the OLD name is caught by nothing but a person reading the file —
         * which is the reason not to write one, and the reason this paragraph replaced a promise with a
         * warning.
         */
        public static final String SOP_CONFORMANCE = "sop_conformance";

        /**
         * Defined-but-inert: the signal is seeded and listable but never produces events. Retained for
         * any future placeholder built-in.
         */
        public static final String INERT = "inert";

        /**
         * The kinds that cannot run without the standalone classify-service, because their score comes from
         * a model resident in it rather than from anything this process can compute.
         *
         * <p>Stated once, here, because two different questions need the same answer and had started to
         * answer it separately: which debug family a classifier belongs to, and — launch requirement J1 —
         * whether the encoder deployment can be scaled to zero. A set that says "frustration and
         * groundedness" in two places is a set that will say different things after the third head lands.
         *
         * <p>{@link #SOP_CONFORMANCE} is deliberately NOT here despite its default {@code encoder-mode=http}
         * reaching the service's {@code /embed}: it needs the service only when an enabled project has a
         * HEAD-CARRYING bundle deployed (deterministic bundles never embed), and its capability flag is
         * targeted on for nobody. Revisit this membership when {@code sop_conformance_enabled} first turns on.
         */
        public static final Set<String> ENCODER_BACKED = Set.of(FRUSTRATION, GROUNDEDNESS);
    }
}
