// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import org.jspecify.annotations.Nullable;

/**
 * The numbers a {@link FindingAdapter} reports for the finding and case pages, typed for the generated frontend types.
 * Learning fields are null when the reference is a fixed limit. @param arlTarget the run length between false alarms
 * the decision interval was tuned to @param learningUntil trials the reference learns from before the detector arms
 * @param words display text written by the adapter
 */
public record ClassifierDetail(
        RateDetail rate,
        @Nullable Long baselineFlagged,
        @Nullable String flagLabel,
        @Nullable Double flagThreshold,
        @Nullable Long arlTarget,
        @Nullable Double minDecisionInterval,
        @Nullable Long learningUntil,
        Words words) {

    /** Written by the adapter, so the open code holds no classifier's wording. */
    public record Words(
            String unitOne,
            String unitMany,
            String figureNote,
            String referenceLabel,
            @Nullable String referenceDetail,
            @Nullable String thresholdLine) {}
}
