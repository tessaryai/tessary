// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.judge.model.IssueResult.AccountLine;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code end_user_issue.diagnostics} jsonb: the numbers behind an issue's sentence, as the judge run writes them.
 * Null where the verdict computed none.
 *
 * @param stopped compared hit user IDs that probably stopped in the {@code h} days after a hit
 * @param expected stops expected in that time from similar user IDs not yet hit
 * @param upper the most user IDs lost if every hit user ID silent today stays away
 * @param h the exposure window in days
 * @param lateHits hit user IDs first hit too late to count
 * @param unmatched hit user IDs with no similar user IDs to compare with
 * @param tVis days after the last use at which a stop starts to show for the hit user IDs' main stratum; "cannot
 *     measure yet" and "hit less than N days ago" print it
 * @param popHigh the most extra stops among all user IDs of the reach after the start; "moved, not added" prints it
 * @param firstEvidenceDay the issue's first evidence day, the start "moved, not added" names
 * @param estimated hit user IDs in the estimate, the N of "few accounts"
 * @param accounts the effective number of accounts, a whole number; only for few accounts
 * @param accountLines the up to 3 accounts with the most hit user IDs; only for few accounts
 */
public record IssueDiagnostics(
        @JsonProperty("stopped") @Nullable Double stopped,
        @JsonProperty("expected") @Nullable Double expected,
        @JsonProperty("upper") @Nullable Double upper,
        @JsonProperty("h") @Nullable Integer h,
        @JsonProperty("late_hits") @Nullable Integer lateHits,
        @JsonProperty("unmatched") @Nullable Integer unmatched,
        @JsonProperty("t_vis") @Nullable Integer tVis,
        @JsonProperty("pop_high") @Nullable Double popHigh,
        @JsonProperty("first_evidence_day") @Nullable Integer firstEvidenceDay,
        @JsonProperty("estimated") @Nullable Integer estimated,
        @JsonProperty("accounts") @Nullable Integer accounts,
        @JsonProperty("account_lines") @Nullable List<AccountLine> accountLines) {}
