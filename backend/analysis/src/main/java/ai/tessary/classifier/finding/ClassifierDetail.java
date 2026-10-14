// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import org.jspecify.annotations.Nullable;

/**
 * The numbers a finding's owning {@link FindingAdapter} reports for the finding and case pages: the flagged rate
 * against its reference, how a trial counts as flagged, and the detector's tuning. Typed so the generated frontend
 * types read it. The learning fields are null when the reference is a fixed limit rather than a learned rate.
 *
 * @param rate the flagged rate against the reference, the shape a built-in rate finding's {@code rate} has
 * @param baselineFlagged how many trials the reference window flagged
 * @param flagLabel the label a trial is flagged under
 * @param flagThreshold the score above which a trial counts as flagged
 * @param arlTarget the run length between false alarms the decision interval was tuned to
 * @param minDecisionInterval the floor on the CUSUM decision interval
 * @param learningUntil how many trials the reference learns from before the detector arms
 * @param words the display text the pages show, written by the adapter
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

    /**
     * The display text the pages render around the numbers. The adapter writes it, so the open code holds no wording
     * of any one classifier.
     *
     * @param unitOne the noun for one trial
     * @param unitMany the noun for many trials
     * @param figureNote the note beside "What changed"
     * @param referenceLabel what the reference rate is, shown after the percentage
     * @param referenceDetail the line under the reference
     * @param thresholdLine when a trial counts as flagged
     */
    public record Words(
            String unitOne,
            String unitMany,
            String figureNote,
            String referenceLabel,
            @Nullable String referenceDetail,
            @Nullable String thresholdLine) {}
}
