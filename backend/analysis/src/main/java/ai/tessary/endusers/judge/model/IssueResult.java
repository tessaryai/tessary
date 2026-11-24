// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.Verdict;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Verdicts, numbers, rank, groups and curves of spec 8.6 to 8.9, by lineage key. */
public record IssueResult(Map<String, LineageResult> byLineage) {

    /**
     * Numbers are null where the verdict shows none.
     *
     * @param rank place in the list, from 1
     * @param groupId the group of findings that fire together; null when alone
     * @param ageHit median days since the first hit of the covered hit IDs
     * @param tVis days after the last use at which a stop starts to show for the issue's main stratum
     * @param pop extra stops among all users of the reach after the start, with {@code popHigh}; null when not read
     * @param silentHitIds key indexes of hit IDs silent now; only for evidence of loss and few accounts
     */
    public record LineageResult(
            Verdict verdict,
            boolean shown,
            int usersHit,
            @Nullable Double lost,
            @Nullable Double low,
            @Nullable Double high,
            int rank,
            @Nullable String groupId,
            double ageHit,
            int tVis,
            double z,
            double visible,
            @Nullable Double pop,
            @Nullable Double popHigh,
            boolean contradicted,
            List<CurvePoint> curve,
            List<Integer> silentHitIds) {}

    /** One weekly step of the retention curve "hit against not hit", with its band and at-risk counts. */
    public record CurvePoint(
            @JsonProperty("week") int week,
            @JsonProperty("hit") double hit,
            @JsonProperty("similar") double similar,
            @JsonProperty("band_low") double bandLow,
            @JsonProperty("band_high") double bandHigh,
            @JsonProperty("at_risk_hit") int atRiskHit,
            @JsonProperty("at_risk_similar") int atRiskSimilar) {}
}
