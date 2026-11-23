// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.rca.RcaDtos.RcaRunView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The case page's fix block on the wire, snake_case. */
public final class FixDtos {

    private FixDtos() {}

    /**
     * Where the case's fix stands. Both null never reaches the wire: the case then has no fix block.
     *
     * @param pr the case's newest pull request, any state @param latestRun the case's newest fix run, any status
     */
    public record FixView(
            @Nullable FixPrView pr,
            @JsonProperty("latest_run") @Nullable FixRunView latestRun) {}

    /** @param state {@code open}, {@code merged} or {@code closed} (without a merge), as Tessary last read it */
    public record FixPrView(
            int number,
            String url,
            String state,
            String branch,
            List<String> causes,
            @JsonProperty("opened_at") String openedAt,
            @JsonProperty("updated_at") String updatedAt) {

        static FixPrView of(FixPrRow row) {
            return new FixPrView(
                    row.prNumber(),
                    row.prUrl(),
                    row.state(),
                    row.branch(),
                    row.causes(),
                    row.openedAt(),
                    row.updatedAt());
        }
    }

    /**
     * @param status {@code queued}, {@code running}, {@code done} or {@code failed} @param outcome set when done:
     * {@code pr_opened}, {@code commit_added}, {@code no_change}, {@code no_fix} or {@code skipped} @param reason a
     * stable code for skipped, no_fix and failed runs: a gate ({@code no_repo}, {@code read_only},
     * {@code no_frontier_model}, {@code no_fixable_cause}, {@code declined}), why there was no change
     * ({@code agent_declined}, {@code check_failed}, {@code empty_patch}, {@code patch_over_limit},
     * {@code patch_refused}), {@code interrupted}, or an error code @param note the agent's own sentence @param prUrl
     * the pull request the run opened or added to @param run the run bar; null until the worker starts the run
     */
    public record FixRunView(
            String id,
            String status,
            @Nullable String outcome,
            @Nullable String reason,
            @Nullable String note,
            @JsonProperty("pr_url") @Nullable String prUrl,
            List<String> causes,
            List<FixCheckView> checks,
            @JsonProperty("patch_files") int patchFiles,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @Nullable RcaRunView run) {}

    public record FixCheckView(
            String command, String result, @Nullable String note) {

        static FixCheckView of(FixCheck c) {
            return new FixCheckView(c.command(), c.result(), c.note());
        }
    }
}
