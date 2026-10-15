// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.pipeline.CallSiteFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A detector that calls a model per item overrides {@link #detectBatch} or {@link #sweepBatch} to amortize its serving
 * calls.
 */
public interface BuiltInDetector {

    String kind();

    /** Pure and side-effect-free: the worker owns persistence. A null {@code config} means the detector's defaults. */
    Detection detect(SubstrateObservation obs, @Nullable String config);

    /** Index-aligned with {@code batch}. */
    default List<Detection> detectBatch(List<SubstrateObservation> batch, @Nullable String config) {
        List<Detection> out = new ArrayList<>(batch.size());
        for (SubstrateObservation obs : batch) out.add(detect(obs, config));
        return out;
    }

    /**
     * A detector that records every item it scored overrides this to write those rows under {@code signal}'s id;
     * {@link #detect} and {@link #detectBatch} stay side-effect-free.
     */
    default List<Detection> sweepBatch(
            ClassifierRow signal, List<SubstrateObservation> batch, @Nullable String config) {
        return detectBatch(batch, config);
    }

    /**
     * {@link ClassifierService#rewindForCallSiteFact} rewinds only signals that declare a changed fact, so reading a
     * call-site column without declaring it here strands history when the fact changes.
     */
    default Set<CallSiteFact> callSiteFactsRead() {
        return Set.of();
    }

    final class Kind {
        private Kind() {}

        public static final String FRUSTRATION = "frustration";

        public static final String SECRET_LEAK = "secret_leak";

        public static final String MALFORMED_OUTPUT = "malformed_output";

        /** Gated to call sites whose declared {@code shape} carries verifiable source content. */
        public static final String GROUNDEDNESS = "groundedness";

        /** Compiled once at definition time; no model call at evaluation. */
        public static final String REGEX = "regex";

        /**
         * No {@link BuiltInDetector}: dispatched through the {@code ClassifierSweep} on
         * {@link ClassifierModelModule.Grain#WINDOW}. It labels nothing, since slow is not bad.
         */
        public static final String DURATION_DRIFT = "duration_drift";

        /**
         * Same structure as {@link #DURATION_DRIFT}. A model with no published rate is unpriced, not free, so its turn
         * leaves the distribution; the cursor only moves forward, so that traffic stays unscored.
         */
        public static final String COST_DRIFT = "cost_drift";

        /**
         * No sweep: its rate is recomputed from an hourly aggregate on every read; the entry exists to make it
         * listable, gated and switchable.
         */
        public static final String TOOL_ERROR = "tool_error";

        /** Kinds whose score comes from the external groundedness model server. */
        public static final Set<String> ENCODER_BACKED = Set.of(GROUNDEDNESS);
    }
}
