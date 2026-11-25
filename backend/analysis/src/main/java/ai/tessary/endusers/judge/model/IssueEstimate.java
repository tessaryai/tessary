// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The estimate of spec 8.3 to 8.5 with the issue's own null, by lineage key. An issue with no comparable hit ID has no
 * entry.
 *
 * @param h {@code H}, the exposure window in days
 */
public record IssueEstimate(Map<String, LineageEstimate> byLineage, int h) {

    /**
     * Rates are before the null's mean {@code b0} is taken off.
     *
     * @param usersHit covered hit IDs
     * @param nEst hit IDs in the estimate
     * @param nM hit IDs with a matched exposed trial
     * @param sumLo stops seen in exposed time with {@code y_lo}; {@code sumHi} with {@code y_hi}
     * @param expLo stops expected in that time from like IDs not yet hit
     * @param controlIds IDs with an unexposed trial on the reach
     * @param lateHits hit IDs first hit more than 7 days after their last series day
     * @param unmatched hit IDs with no like IDs to compare with
     * @param estimateIds key indexes of the hit IDs in the estimate; a covered hit ID not in it is a late hit
     * @param withoutAccounts the same estimate with the account cell dropped; computed only when clusters exist and
     *     {@code accEff} is under 5, else null
     */
    public record LineageEstimate(
            int usersHit,
            int nEst,
            int nM,
            double rateLo,
            double rateHi,
            double se,
            double b0,
            double s0,
            double nullMax,
            int draws,
            double accEff,
            double sumLo,
            double sumHi,
            double expLo,
            int trials,
            int controlIds,
            int lateHits,
            int unmatched,
            Set<Integer> estimateIds,
            @Nullable LineageEstimate withoutAccounts) {}
}
