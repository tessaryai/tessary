// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

/**
 * One RCA job on the unified {@code job} table ({@code kind='rca'}): the FINDING to analyse, its
 * subject as the report renders it, and the triggering user
 * ({@code createdBy} — the principal the agentic lane's ephemeral MCP key is issued to), all in the
 * {@code payload} jsonb.
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
        String createdBy) {

    public static final String FAILED = "failed";
}
