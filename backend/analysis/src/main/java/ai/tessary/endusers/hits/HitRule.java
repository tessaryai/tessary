// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which refs of a finding count as user IDs hit (spec 8.1) and what the detector reaches (spec 8.2). The hit sweep
 * stores every evidence role; the rule picks the hit roles when the rows are read.
 *
 * @param name stored as {@code end_user_hit_summary.hit_rule}
 * @param roleTiers the hit roles are the first tier with a stored row
 * @param onePerTrace a trace written as a trace row and a span row counts once
 */
public record HitRule(
        String name,
        Rows rows,
        List<Set<String>> roleTiers,
        boolean onePerTrace,
        Reach reach,
        Completeness completeness) {

    /** Where the hit rows come from. Detections stand in for evidence that keeps only some witnesses. */
    public enum Rows {
        EVIDENCE,
        MALFORMED_OUTPUT_DETECTIONS,
        SECRET_LEAK_DETECTIONS,
        NONE
    }

    /** {@code MEMBER}: the member rows are every trial. {@code CALL_SITE}: the call site's traffic. */
    public enum Reach {
        MEMBER,
        CALL_SITE,
        NONE
    }

    /** {@code NOT_APPLICABLE}: the issue shifts a measure for all traffic and has no hit user IDs. */
    public enum Completeness {
        COMPLETE,
        NOT_COMPLETE,
        NOT_APPLICABLE
    }

    /** For a detector kind no source has a rule for: witnesses when any exist, else the other flagged roles. */
    public static final HitRule DEFAULT = new HitRule(
            "default",
            Rows.EVIDENCE,
            List.of(Set.of(Role.WITNESS), Set.of(Role.MEMBER, Role.EXEMPLAR, Role.CHANGEPOINT)),
            false,
            Reach.MEMBER,
            Completeness.NOT_COMPLETE);

    public Set<String> hitRoles(Set<String> storedRoles) {
        for (Set<String> tier : roleTiers) {
            if (!Collections.disjoint(tier, storedRoles)) return tier;
        }
        return Set.of();
    }

    /** True when no hit is missing: a rule that has no hit user IDs misses none. */
    public boolean complete() {
        return completeness != Completeness.NOT_COMPLETE;
    }

    public boolean readsDetections() {
        return rows == Rows.MALFORMED_OUTPUT_DETECTIONS || rows == Rows.SECRET_LEAK_DETECTIONS;
    }
}
