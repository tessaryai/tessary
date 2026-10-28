// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/**
 * The single implementation both surfaces (Classifiers and Triage) delegate to, so a finding and its case are named
 * identically. The cause key's spelling is frozen identity; the copy may change. Never invents a number: an unreadable
 * blob falls back to the cause key.
 */
public final class FindingTitle {

    private FindingTitle() {}

    /**
     * Dispatch is on cause kind: one cause's reader parsing another's blob would turn a missing field into a wrong
     * sentence.
     */
    public static String of(FindingRow finding) {
        return switch (finding.causeKind()) {
            case FindingRow.Cause.DISTRIBUTION_SHIFT -> metric(finding);
            case FindingRow.Cause.RATE_SHIFT -> toolError(finding);
            case FindingRow.Cause.ARMED_WINDOW -> armed(finding);
            case FindingRow.Cause.MALFORMED_RATE -> malformedRate(finding);
            case FindingRow.Cause.FRUSTRATION_RATE -> frustrationRate(finding);
            case FindingRow.Cause.GROUNDEDNESS_RATE -> groundednessRate(finding);
            default -> stored(finding);
        };
    }

    private static String stored(FindingRow finding) {
        String title = finding.title();
        return title == null || title.isBlank() ? finding.nativeCauseKey() : title;
    }

    /** The same string both case sources used, so a finding and its case match even when degraded. */
    private static String unreadable(FindingRow finding) {
        return finding.nativeCauseKey() + " has shifted";
    }

    private static String metric(FindingRow finding) {
        MetricFindingEvidence.Read read = MetricFindingEvidence.read(finding.payloadJson());
        if (read == null) return unreadable(finding);
        return MetricFindingEvidence.title(read.measure(), read.bucketKey(), read.ratio(), read.direction());
    }

    /**
     * No rate in the headline: two tools' error rates are not comparable. Direction comes off the cause key, where it
     * is persisted.
     */
    private static String toolError(FindingRow finding) {
        ToolErrorEvidence.Read read = ToolErrorEvidence.read(finding.payloadJson());
        if (read == null) return unreadable(finding);
        String key = finding.nativeCauseKey();
        int last = key.lastIndexOf(':');
        String movement = last >= 0 && "down".equals(key.substring(last + 1)) ? "reduced" : "elevated";
        return String.format(
                Locale.ROOT, "%s showing %s error rates", ToolErrorEvidence.shortName(read.bucketKey()), movement);
    }

    /** Built from the payload and call site: the cause key leads with a classifier id, meaningless in a title. */
    private static String armed(FindingRow finding) {
        String facet = FindingPayload.text(finding.payloadJson(), "facet");
        if (facet == null) return finding.nativeCauseKey();
        String callSite = finding.callSiteId();
        return facet + " in " + (callSite == null ? "agent" : callSite) + " output";
    }

    /** No rate in the headline, for the reason {@link #toolError} gives. */
    private static String malformedRate(FindingRow finding) {
        String callSite = finding.callSiteId();
        return (callSite == null ? finding.nativeCauseKey() : callSite) + " outputs failing their schema";
    }

    /** Rates are in the headline: both are shares of the same call site's own session-hours. */
    private static String frustrationRate(FindingRow finding) {
        String callSite = finding.callSiteId() == null ? finding.nativeCauseKey() : finding.callSiteId();
        JsonNode body = finding.payload();
        if (!body.path("baseline_rate").isNumber() || !body.path("current_rate").isNumber()) {
            return "Frustrated sessions increased on " + callSite;
        }
        return "Frustrated sessions increased from "
                + pct(body.path("baseline_rate").asDouble()) + " to "
                + pct(body.path("current_rate").asDouble()) + " on " + callSite;
    }

    /**
     * Direction only: the flagged rate counts false alarms and misses, so the direction is reliable where the size is
     * not.
     */
    private static String groundednessRate(FindingRow finding) {
        String callSite = finding.callSiteId() == null ? finding.nativeCauseKey() : finding.callSiteId();
        return "Answers on " + callSite + " became less grounded";
    }

    /** Shared with the case source's basis line so a case and its finding quote the same numbers. */
    public static String pct(double rate) {
        double p = rate * 100.0;
        return p > 0 && p < 0.1 ? String.format(Locale.ROOT, "%.2f%%", p) : String.format(Locale.ROOT, "%.1f%%", p);
    }
}
