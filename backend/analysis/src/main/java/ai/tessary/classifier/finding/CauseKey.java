// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * Scopes each classifier's cause into {@code finding.cause_key}. {@code ux_finding_live} is one unique index over
 * {@code (project_id, classifier_key, cause_key)}, so the scope must live inside the key. The writer and the migration
 * {@code 0086} backfill must spell these identically, or every live cause opens a second finding.
 */
public final class CauseKey {

    private CauseKey() {}

    /** The baseline id carries environment scope; dropping it merges a staging shift into the production finding. */
    public static String metricDrift(String baselineId, String causeKey) {
        return baselineId + ":" + causeKey;
    }

    /** Already project-scoped and hangs off no fitted state, so there is nothing to prefix. */
    public static String toolError(String causeKey) {
        return causeKey;
    }

    /** The id, not the key: a key is renameable and a live finding must not detach from its classifier. */
    public static String perSpanClassifier(String classifierId) {
        return classifierId;
    }

    /**
     * One finding per call site and facet: a leaked AWS key at one site and a GitHub token at another are two fixes. A
     * span with no call site keeps an empty middle segment.
     */
    public static String perSpanClassifierFacet(String classifierId, @Nullable String callSiteId, String facet) {
        return classifierId + ":" + (callSiteId == null ? "" : callSiteId) + ":" + facet;
    }

    /** The schema a failure is measured against belongs to the call site. */
    public static String malformedOutput(String classifierId, String callSiteId) {
        return classifierId + ":" + callSiteId;
    }

    public static String frustration(String classifierId, String callSiteId) {
        return classifierId + ":" + callSiteId;
    }

    public static String groundedness(String classifierId, String callSiteId) {
        return classifierId + ":" + callSiteId;
    }

    /** Strips both fixed affixes rather than splitting on colons, because a bucket key may contain them. */
    public static String toolOf(String causeKey) {
        String stripped =
                causeKey.startsWith("tool_error_rate:") ? causeKey.substring("tool_error_rate:".length()) : causeKey;
        int last = stripped.lastIndexOf(':');
        if (last < 0) return stripped;
        String tail = stripped.substring(last + 1);
        return "up".equals(tail) || "down".equals(tail) ? stripped.substring(0, last) : stripped;
    }
}
