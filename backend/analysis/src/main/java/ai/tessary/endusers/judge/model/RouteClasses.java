// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.RouteClass;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The route classes of spec 1, by route number.
 *
 * @param lenient no route is of class A, so B and M routes count as paced (activity basis "lenient")
 */
public record RouteClasses(Map<Integer, Route> byRoute, boolean lenient) {

    /**
     * @param classSinceDay the day the route took this class
     * @param nearCut a statistic is within the class margin of its cut, so the stored class was kept
     */
    public record Route(
            RouteClass routeClass, @Nullable Integer classSinceDay, RouteStatistics statistics, boolean nearCut) {}

    /**
     * The statistics of spec 1 for one route, stored as {@code end_user_route.statistics}. A null quantile or
     * concentration had too few observations.
     */
    public record RouteStatistics(
            @JsonProperty("n") long traces,
            @JsonProperty("null_share") double nullShare,
            @JsonProperty("inp") double inputShare,
            @JsonProperty("par") double parentShare,
            @JsonProperty("sec0") double second0Share,
            @JsonProperty("min5") double minute5Share,
            @JsonProperty("follow") double follow,
            @JsonProperty("dq10") @Nullable Double delayQ10,
            @JsonProperty("dq90") @Nullable Double delayQ90,
            @JsonProperty("conc") @Nullable Double concentration,
            @JsonProperty("sched_r") double scheduleShare,
            @JsonProperty("co_anchor") @Nullable Double coAnchor) {}
}
