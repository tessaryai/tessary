// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CANNOT_BE_NAMED_FACTOR;
import static ai.tessary.endusers.judge.JudgeConstants.CANNOT_BE_NAMED_PLUS;
import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.F_MAX;
import static ai.tessary.endusers.judge.JudgeConstants.HISTORY_FLOOR_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.NAMED_RIGHT_CUT;
import static ai.tessary.endusers.judge.JudgeConstants.ONE_DAY_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.RELIABILITY_MIN_ANCHORS;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Stratum;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What the Probably stopped stage may show: the level 2 withholding rule, first rule that applies, with the notice
 * "stops cannot be named yet" of spec 7.2 as the full state's variant.
 *
 * <ol>
 *   <li>{@code history_too_short}: facts are kept for under 120 days (guard {@code history-too-short}). Numbers
 *       {@code horizon_days}, {@code horizon_days_needed}.
 *   <li>{@code not_judged_yet}: fewer than 4 past dates, or no record was named at them, so {@code named_right} is null
 *       (the null part is guard {@code not-judged-without-past-check}). Numbers {@code anchors},
 *       {@code anchors_needed}, and with too few dates {@code days_needed}: one week a missing date, because the dates
 *       are on the week grid.
 *   <li>{@code cannot_tell_yet}: {@code F} is at its clamp of 42 days (guard {@code cannot-tell-yet}). Number
 *       {@code return_cycle_days}.
 *   <li>{@code mixed_id_formats}: a format that holds a covered ID has a one-day share of 0.3 or more (guard
 *       {@code mixed-id-formats}). Number {@code one_day_share}, the largest.
 *   <li>{@code expected_only}: {@code named_right} under 0.8, or null with its guard off. Numbers
 *       {@code stopped_expected}, {@code stopped_low}, {@code stopped_high}, {@code named_right} when measured,
 *       {@code named_right_needed}.
 *   <li>{@code cannot_be_named_yet} when {@code stopped_expected >= 1.5 * stopped_named + 20}, else
 *       {@code expected_and_named}. Numbers {@code stopped_expected}, {@code stopped_low}, {@code stopped_high},
 *       {@code stopped_named}, and for the notice {@code plateau}, the most one silence proves here (the plateau of
 *       stratum E, else K1).
 * </ol>
 */
public final class StopStageRule {

    private StopStageRule() {}

    public record Decision(StopStageState state, Map<String, Double> numbers) {}

    public static Decision decide(
            Guards guards,
            @Nullable Integer horizonDays,
            StopOdds odds,
            @Nullable Double coveredOneDayShare,
            StageCounts counts) {
        if (guards.historyTooShort() && horizonDays != null && horizonDays < HISTORY_FLOOR_DAYS) {
            return new Decision(
                    StopStageState.HISTORY_TOO_SHORT,
                    Map.of("horizon_days", (double) horizonDays, "horizon_days_needed", (double) HISTORY_FLOOR_DAYS));
        }
        int anchors = odds.anchors().size();
        Double namedRight = odds.namedRight();
        if (anchors < RELIABILITY_MIN_ANCHORS || (guards.notJudgedWithoutPastCheck() && namedRight == null)) {
            Map<String, Double> numbers = new TreeMap<>();
            numbers.put("anchors", (double) anchors);
            numbers.put("anchors_needed", (double) RELIABILITY_MIN_ANCHORS);
            if (anchors < RELIABILITY_MIN_ANCHORS) {
                numbers.put("days_needed", (double) (DAYS_PER_WEEK * (RELIABILITY_MIN_ANCHORS - anchors)));
            }
            return new Decision(StopStageState.NOT_JUDGED_YET, numbers);
        }
        if (guards.cannotTellYet() && odds.f() >= F_MAX) {
            return new Decision(StopStageState.CANNOT_TELL_YET, Map.of("return_cycle_days", (double) odds.f()));
        }
        if (guards.mixedIdFormats() && coveredOneDayShare != null && coveredOneDayShare >= ONE_DAY_SHARE) {
            return new Decision(StopStageState.MIXED_ID_FORMATS, Map.of("one_day_share", coveredOneDayShare));
        }
        Map<String, Double> numbers = new TreeMap<>();
        numbers.put("stopped_expected", counts.stoppedExpected());
        numbers.put("stopped_low", counts.stoppedLow());
        numbers.put("stopped_high", counts.stoppedHigh());
        if (namedRight == null || namedRight < NAMED_RIGHT_CUT) {
            if (namedRight != null) numbers.put("named_right", namedRight);
            numbers.put("named_right_needed", NAMED_RIGHT_CUT);
            return new Decision(StopStageState.EXPECTED_ONLY, numbers);
        }
        numbers.put("stopped_named", (double) counts.stoppedNamed());
        if (counts.stoppedExpected() >= CANNOT_BE_NAMED_FACTOR * counts.stoppedNamed() + CANNOT_BE_NAMED_PLUS) {
            numbers.put("plateau", plateau(odds));
            return new Decision(StopStageState.CANNOT_BE_NAMED_YET, numbers);
        }
        return new Decision(StopStageState.EXPECTED_AND_NAMED, numbers);
    }

    /** The largest one-day share over the formats ({@code prefix/length}) that hold a covered ID; null with none. */
    static @Nullable Double coveredOneDayShare(Facts facts, Identity identity) {
        Double largest = null;
        for (Map.Entry<Integer, Identity.IdIdentity> e : identity.byId().entrySet()) {
            if (!e.getValue().covered()) continue;
            Double share = identity.oneDayShareByShape()
                    .get(IdentityFlags.shapeKey(facts.ids().get(e.getKey())));
            if (share != null && (largest == null || share > largest)) largest = share;
        }
        return largest;
    }

    private static double plateau(StopOdds odds) {
        Double e = odds.plateau().get(Stratum.E);
        if (e != null) return e;
        return odds.plateau().getOrDefault(Stratum.K1, 0.0);
    }
}
