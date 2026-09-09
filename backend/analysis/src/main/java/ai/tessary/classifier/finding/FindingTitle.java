// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import java.util.Locale;

/**
 * What a finding is called, in a sentence — {@code "policy-gpt.member-chat turns are 1.47× more expensive"}
 * rather than {@code "cost:policy-gpt.member-chat:dearer:pinned"}.
 *
 * <p>Conformance has its own sentence in {@code ConformanceFindingViews.title}, on the other side of the
 * triage seam: its rates are columns rather than an evidence blob, so it shares the reason this class
 * exists (a finding and its case are named identically) without sharing a line of its dispatch.
 *
 * <p><b>One implementation, because two surfaces name the same object.</b> A finding on the Classifiers
 * page and the case it opens in Triage are the same event seen at two stages, and they used to be named
 * by two different pieces of code: Triage built a sentence from the stored evidence, while Classifiers
 * rendered the raw cause key. A reader who saw both had to work out for themselves that
 * {@code cost:policy-gpt.member-chat:dearer:pinned} and "policy-gpt.member-chat turns are 1.47× more
 * expensive" were one thing. The case sources now delegate here, so the two cannot drift apart by
 * construction — a wording change lands on both surfaces or on neither.
 *
 * <p>The key still spells the direction {@code dearer} and the sentence no longer does. That is
 * deliberate: {@code cause_key} is the identity of a finding, so its spelling is frozen, while the copy
 * is free to change. See {@link MetricFindingEvidence#directionWord} beside
 * {@link MetricFindingEvidence#directionPhrase}.
 *
 * <p><b>It never invents a number.</b> Every title is read back out of the finding's own evidence blob
 * through that classifier's reader, exactly as {@code MetricDriftSource} already did it. When the blob
 * is missing or unreadable — a behaviour-drift cause, which carries no measured shift, or a row written
 * before the evidence was recorded — this falls back to a form of the cause key rather than guessing at
 * a magnitude. A title that says less is survivable; one that says the wrong multiple is not.
 */
public final class FindingTitle {

    private FindingTitle() {}

    /**
     * The sentence for this finding, or its cause key when the evidence cannot be read.
     *
     * <p>Dispatch is on the cause kind rather than on which reader happens to parse the blob: the two
     * measured causes each own an evidence schema, and asking one to read the other's would turn a
     * missing field into a wrong sentence instead of an honest fallback.
     */
    public static String of(FindingRow finding) {
        return switch (finding.causeKind()) {
            case FindingRow.Cause.DISTRIBUTION_SHIFT -> metric(finding);
            case FindingRow.Cause.RATE_SHIFT -> toolError(finding);
            // Omission, novelty and surprisal are shapes rather than magnitudes — there is no "by how
            // much" to put in a sentence, and the cause key already reads as the action sequence.
            default -> finding.nativeCauseKey();
        };
    }

    /**
     * What a measured cause is called when its numbers are gone. Not the bare key: this is the string
     * both case sources already used, and the point of this class is that a finding and its case are
     * named identically even in the degraded path.
     */
    private static String unreadable(FindingRow finding) {
        return finding.nativeCauseKey() + " has shifted";
    }

    private static String metric(FindingRow finding) {
        MetricFindingEvidence.Read read = MetricFindingEvidence.read(finding.payloadJson());
        if (read == null) return unreadable(finding);
        return MetricFindingEvidence.title(read.measure(), read.bucketKey(), read.ratio(), read.direction());
    }

    /**
     * {@code "search_docs showing elevated error rates"}.
     *
     * <p><b>No rate in the headline.</b> It used to carry one, and the number it carried was the tool's
     * lifetime average, which read 6.2% during an 80% outage. Measuring it over the run since onset fixes
     * the number but not the shape of the sentence: a percentage in a headline invites a reader to weigh
     * two findings by comparing them, and two tools' error rates are not comparable. The criticality
     * badge carries "how much does this matter"; the finding's basis carries the arithmetic.
     *
     * <p>Direction comes off the cause key, which is where it is persisted. This read "up from"
     * unconditionally, mislabelling every improvement the down arm found.
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

    /**
     * A failure rate, at the precision that keeps a small one from rounding to zero. Shared with the
     * case source's basis line so a case and its finding quote the same two numbers.
     */
    public static String pct(double rate) {
        double p = rate * 100.0;
        return p > 0 && p < 0.1 ? String.format(Locale.ROOT, "%.2f%%", p) : String.format(Locale.ROOT, "%.1f%%", p);
    }
}
