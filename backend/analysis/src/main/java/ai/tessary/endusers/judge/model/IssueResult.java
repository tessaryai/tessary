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
     * @param firstEvidenceDay the issue's first evidence day, the start the second contrast reads; null with no
     *     evidence
     * @param estimated {@code n_est}, the hit IDs in the estimate; null when the estimate was not reached
     * @param accounts {@code acc_eff} of the first estimate rounded to a whole number; only for few accounts
     * @param accountLines see {@link AccountLine}; only for few accounts, else empty
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
            List<Integer> silentHitIds,
            @Nullable Integer firstEvidenceDay,
            @Nullable Integer estimated,
            @Nullable Integer accounts,
            List<AccountLine> accountLines) {}

    /**
     * One account of a few-accounts verdict (spec 8.7): {@code hit} of its {@code ids} IDs were hit and {@code silent}
     * of those are silent since {@code silentSinceDay}. No ID or cluster text: the frontend words the number.
     *
     * @param account 1 for the account with the most hit IDs, up to 3
     * @param ids every ID of the account
     * @param hit hit IDs of the account that the estimate can follow
     * @param silent those hit IDs whose {@code p_stop} is at the stop cut or above
     * @param silentSinceDay the last series day of the silent IDs that was seen last; null when none is silent
     */
    public record AccountLine(
            @JsonProperty("account") int account,
            @JsonProperty("ids") int ids,
            @JsonProperty("hit") int hit,
            @JsonProperty("silent") int silent,
            @JsonProperty("silent_since_day") @Nullable Integer silentSinceDay) {}

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
