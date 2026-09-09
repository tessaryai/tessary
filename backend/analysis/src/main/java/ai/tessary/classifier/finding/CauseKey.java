// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

/**
 * How each classifier's cause identity is scoped into {@code finding.cause_key}.
 *
 * <p>{@code ux_finding_live} is one partial unique index over {@code (project_id, classifier_key,
 * cause_key)}, replacing the four scoped indexes the two old finding tables carried. The scope a cause
 * used to be unique WITHIN therefore has to live inside the key — otherwise two epochs of one project,
 * or two environments' baselines, silently merge into one finding.
 *
 * <p>One place, because the writer and the backfill have to spell these identically: a composition that
 * disagreed with migration {@code 0086} would open a second finding for every cause that already had
 * one, on the first sweep after deploy.
 */
public final class CauseKey {

    private CauseKey() {}

    /** {@code <profile_id>:<cause_kind>:<key>:<workflow_key>} — the profile-scoped shape (0024/0033). */
    public static String behaviorDrift(String profileId, String causeKind, String causeKey, String workflowKey) {
        return profileId + ":" + causeKind + ":" + causeKey + ":" + workflowKey;
    }

    /**
     * {@code <baseline_id>:<cause_key>} — the baseline-scoped shape (0042). The baseline id carries
     * environment scope, so dropping it merges a staging shift into the production finding.
     */
    public static String metricDrift(String baselineId, String causeKey) {
        return baselineId + ":" + causeKey;
    }

    /**
     * The tool-error key verbatim: {@code tool_error_rate:<bucket>:<direction>} is already
     * project-scoped and hangs off no fitted state (0049), so there is nothing to prefix.
     */
    public static String toolError(String causeKey) {
        return causeKey;
    }

    /**
     * {@code <rule_id>:<kind>}. The kind is load-bearing rather than decoration: 0072 widened
     * conformance's open-uniqueness to {@code (rule_id, kind)} because a fit-time baseline audit and a
     * windowed drift test are two claims about one rule, and keyed on the rule alone each would
     * overwrite the other's evidence.
     */
    public static String conformance(String ruleId, String kind) {
        return ruleId + ":" + kind;
    }

    /**
     * The signal row's id, verbatim — the per-span classifiers' shape. A classifier's arming has exactly
     * one cause ("this classifier is firing more than its owner said it should"), so the scope IS the
     * classifier, and the id is used rather than the key because a key is renameable and a live finding
     * must not detach from its classifier when someone edits the name.
     */
    public static String perSpanClassifier(String classifierId) {
        return classifierId;
    }

    /**
     * The tool a {@code tool_error} cause key names — its subject. Stripping both fixed affixes rather
     * than splitting on colons, because a bucket key may contain them.
     */
    public static String toolOf(String causeKey) {
        String stripped =
                causeKey.startsWith("tool_error_rate:") ? causeKey.substring("tool_error_rate:".length()) : causeKey;
        int last = stripped.lastIndexOf(':');
        if (last < 0) return stripped;
        String tail = stripped.substring(last + 1);
        return "up".equals(tail) || "down".equals(tail) ? stripped.substring(0, last) : stripped;
    }
}
