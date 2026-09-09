// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import org.jspecify.annotations.Nullable;

/**
 * One RCA job on the unified {@code job} table ({@code kind='rca'}): the FINDING to analyse, its
 * subject as the report renders it, the span of time it covers, and the triggering user
 * ({@code createdBy} — the principal the agentic lane's ephemeral MCP key is issued to; null on jobs
 * enqueued before it was recorded), all in the {@code payload} jsonb.
 *
 * <p>Natural key {@code dedupe_key = <project>:finding:<finding>} (partial unique {@code ux_job_rca}) —
 * one job, and so one immutable report, per finding; a second press coalesces onto the existing job
 * instead of re-analyzing. It used to be keyed on a mover-event's split hour, which is what a subject
 * with no stored identity forces on you: the finding has an id, so the key is the id. Terminal
 * {@code failed} like the grader-run queue (no dead-letter cooldown — a user can simply trigger again).
 */
public record RcaJobRow(
        String id,
        String projectId,
        String findingId,
        String subjectKind,
        String subjectId,
        String metric,
        String windowFrom,
        String windowSplit,
        String windowTo,
        @Nullable String createdBy,
        String status,
        @Nullable String leaseOwner,
        @Nullable String leaseExpiresAt,
        int attempts,
        @Nullable String lastError,
        String createdAt,
        String updatedAt) {

    public static final String FAILED = "failed";
}
