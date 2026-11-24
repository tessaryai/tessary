// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * The {@code end_user_issue.diagnostics} jsonb: the numbers behind an issue's sentence. Null where the verdict computed
 * none.
 *
 * @param stopped compared hit user IDs that probably stopped in the {@code h} days after a hit
 * @param expected stops expected in that time from similar user IDs not yet hit
 * @param upper the most user IDs lost if every hit user ID silent today stays away
 * @param h the exposure window in days
 * @param lateHits hit user IDs first hit too late to count
 * @param unmatched hit user IDs with no similar user IDs to compare with
 */
public record IssueDiagnostics(
        @JsonProperty("stopped") @Nullable Double stopped,
        @JsonProperty("expected") @Nullable Double expected,
        @JsonProperty("upper") @Nullable Double upper,
        @JsonProperty("h") @Nullable Integer h,
        @JsonProperty("late_hits") @Nullable Integer lateHits,
        @JsonProperty("unmatched") @Nullable Integer unmatched) {}
