// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

/**
 * One {@code kind='rca'} job. {@code createdBy} is the principal the lane's ephemeral MCP key is issued to.
 * {@code dedupe_key = <project>:finding:<finding>} gives one job and one immutable report per finding, so a second
 * press coalesces. Terminal {@code failed} with no dead-letter cooldown: a user can trigger again.
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
