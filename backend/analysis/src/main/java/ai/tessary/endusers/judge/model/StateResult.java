// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.LimitCode;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * States, stages and page notes of spec 7.2 and 9 and the level 2 withholding rule. Numbers are by snake_case name,
 * for the frontend to word.
 *
 * @param stages null on a refused page
 * @param stopStage null on a refused page
 * @param seasonLevel {@code mu} of spec 7.1; 1 when no banner
 */
public record StateResult(
        Map<Integer, IdState> byId,
        @Nullable StageCounts stages,
        Map<SetAsideReason, Integer> setAside,
        @Nullable StopStageState stopStage,
        Map<String, Double> stopStageNumbers,
        List<Banner> banners,
        List<LimitLine> limits,
        @Nullable TrustNumbers trust,
        double seasonLevel) {

    /**
     * @param stage null for held, set-aside and cannot-follow IDs and on a refused page
     * @param cannotFollow vanished in an ID change, so in no stage
     */
    public record IdState(
            UserIdState state,
            @Nullable Substate substate,
            @Nullable Grade grade,
            @Nullable Stage stage,
            int daysSilent,
            @Nullable SetAsideReason setAside,
            boolean cannotFollow) {}

    /**
     * Counts of covered IDs. Active, silent and stopped named add up to {@code counted}; new is part of active.
     *
     * @param stoppedExpected the sum of {@code p_stop}, with its range low to high
     */
    public record StageCounts(
            @JsonProperty("active") int active,
            @JsonProperty("new") int newIds,
            @JsonProperty("silent") int silent,
            @JsonProperty("held") int held,
            @JsonProperty("stopped_named") int stoppedNamed,
            @JsonProperty("stopped_expected") double stoppedExpected,
            @JsonProperty("stopped_low") double stoppedLow,
            @JsonProperty("stopped_high") double stoppedHigh,
            @JsonProperty("counted") int counted) {}

    /** @param routeNos the routes the banner names, by route number; the read gives their names */
    public record Banner(
            @JsonProperty("code") BannerCode code,
            @JsonProperty("numbers") Map<String, Double> numbers,
            @JsonProperty("route_nos") List<Integer> routeNos) {

        public Banner(BannerCode code, Map<String, Double> numbers) {
            this(code, numbers, List.of());
        }
    }

    /** @param ids how many IDs the line concerns, the order in which lines are cut */
    public record LimitLine(
            @JsonProperty("code") LimitCode code,
            @JsonProperty("numbers") Map<String, Double> numbers,
            @JsonProperty("never_cut") boolean neverCut,
            @JsonProperty("ids") int ids) {}

    /** @param caveat the tracer cannot test, so the checks confirm IDs, not people */
    public record TrustNumbers(
            @JsonProperty("named_right") @Nullable Double namedRight,
            @JsonProperty("named_found") @Nullable Double namedFound,
            @JsonProperty("caveat") boolean caveat) {}
}
