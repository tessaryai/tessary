// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.Stratum;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Stop probability and past check of spec 5, 6.1 and 6.2.
 *
 * @param mApp the app's median gap on the clock; null with no gaps
 * @param mAppCalendar the median of each ID's mean calendar gap over IDs with four or more series days, read by
 *     {@code H}; null with none
 * @param f {@code F}, the return cycle in days: the 99th percentile gap, clamped to 14 to 42
 * @param anchors the past dates checked, ascending, at most 12
 * @param namedRight share of IDs named as stopped at the older dates that stayed away; null when not measured
 * @param namedFound share of IDs that stayed away that were named; null when not measured
 * @param plateau the calibrated return curve's level at {@code F}, per stratum
 * @param tVis days after the last use at which half the plateau shows, per stratum that reaches it
 * @param t50 days after the last use at which the calibrated value reaches the stop cut, per stratum that reaches it
 */
public record StopOdds(
        Map<Integer, IdOdds> byId,
        @Nullable Double mApp,
        @Nullable Double mAppCalendar,
        int f,
        List<Integer> anchors,
        List<CalibrationBlock> calibration,
        @Nullable Double namedRight,
        @Nullable Double namedFound,
        Map<Stratum, Double> plateau,
        Map<Stratum, Integer> tVis,
        Map<Stratum, Integer> t50) {

    /**
     * @param pCalibrated the calibrated value before the ID-loss factor, the top of the stop range under ID loss
     * @param pStop the calibrated value after the ID-loss factor
     * @param pStopCalendar for a member of an open shared silence: the value with the silence counted in full, which
     *     decides if the ID is held and tops the held range; null otherwise
     * @param unattended the person is silent while automation runs, and no automation curve exists
     * @param beyond four or more series days and a silence longer than the longest completed gap
     * @param daysSilent calendar days since the last series day
     * @param clockSilence the same silence on the clock of {@link SilenceResult}
     */
    public record IdOdds(
            Stratum stratum,
            double pNominal,
            double pCalibrated,
            double pStop,
            @Nullable Double pStopCalendar,
            boolean unattended,
            boolean beyond,
            int daysSilent,
            double clockSilence) {}

    /** One block of the isotonic calibration; {@code beyond} is null for a block fitted on both kinds. */
    public record CalibrationBlock(
            @JsonProperty("stratum") Stratum stratum,
            @JsonProperty("beyond") @Nullable Boolean beyond,
            @JsonProperty("p_low") double pLow,
            @JsonProperty("p_high") double pHigh,
            @JsonProperty("value") double value,
            @JsonProperty("n") int n) {}
}
