// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.model.JobStatus;
import org.jspecify.annotations.Nullable;

/**
 * One Layer-2 drift-triage job on the unified {@code job} table
 * ({@code kind='triage'}): the finding to analyze, carried in the {@code payload} jsonb.
 *
 * <p><b>No trace, no session, no deploy.</b> The payload used to carry an exemplar trace, the session it
 * belonged to and the version it ran under, all resolved from one sampled row. Nothing ever read the
 * first two, and the third existed only to name a commit in the prompt — one trace's deploy, stated as
 * the finding's. Handing an agent a chosen instance decides which one it anchors on, and it cannot tell
 * our pick from its own draw. The finding id is the whole job now; the population is behind MCP.
 *
 * <p>Natural key {@code dedupe_key = <project_id>:<finding_id>} (unique
 * {@code ux_job_triage}) — one run per look, and a finding only gets a second look when its cause
 * recurs past the re-open threshold.
 * Terminal {@code dead}, not {@code failed}: triage is advisory evidence, so a failed analysis must not
 * re-spend a microVM on every subsequent sweep — but neither may it be unreachable forever, which
 * {@code failed} made it. {@code BehaviorTriageJobRepository#enqueue} splices the shared dead-letter
 * cooldown gate, so a human pressing <em>Run triage</em> revives the job once its floor has passed and
 * the automatic paths (which cannot reach an escalated finding at all) still cannot.
 */
public record BehaviorTriageJobRow(
        String id,
        String projectId,
        String findingId,
        String status,
        @Nullable String leaseOwner,
        @Nullable String leaseExpiresAt,
        int attempts,
        @Nullable String lastError,
        String createdAt,
        String updatedAt) {

    /**
     * The terminal state an exhausted triage parks in — the cooldown-gated {@code dead} the signal and
     * embedding queues already use, so the one revival path ({@code enqueue}'s cooldown gate) and the
     * park statement cannot drift apart. Migration 0022 moved every pre-existing {@code failed} triage
     * row here, so this queue writes and reads exactly one terminal status.
     */
    public static final String DEAD = JobStatus.DEAD;
}
