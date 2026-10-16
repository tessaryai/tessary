// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Shapes a case for any classifier the dedicated sources don't own, such as an org-authored per-span classifier. It
 * has no evidence blob of its own shape, so the case states the fact plainly rather than a before/after pair.
 */
@Component
public class GenericFindingCaseSource implements CaseSource {

    private static final Set<String> DEDICATED = Set.of(
            BuiltInDetector.Kind.DURATION_DRIFT,
            BuiltInDetector.Kind.COST_DRIFT,
            BuiltInDetector.Kind.TOOL_ERROR,
            BuiltInDetector.Kind.MALFORMED_OUTPUT,
            BuiltInDetector.Kind.SECRET_LEAK,
            BuiltInDetector.Kind.FRUSTRATION,
            BuiltInDetector.Kind.GROUNDEDNESS);

    private static final double UNKNOWN_SEVERITY = 0.5;

    @Override
    public boolean owns(String classifierKey) {
        return !DEDICATED.contains(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        CaseKey key = new CaseKey(
                CaseRow.Detector.CLASSIFIER,
                CaseRow.SubjectKind.CLASSIFIER,
                finding.causeKey(),
                finding.classifierKey());
        return new CaseDetection(
                key,
                finding.subjectLabel() != null ? finding.subjectLabel() : finding.nativeCauseKey(),
                finding.callSiteId(),
                finding.id(),
                FindingTitle.of(finding),
                basis(finding),
                severity(finding),
                CaseSource.onset(finding),
                null,
                null,
                null);
    }

    private static String basis(FindingRow finding) {
        String gate = gateSentence(finding);
        String detail = finding.basis();
        return detail == null || detail.isBlank() ? gate : gate + " " + detail;
    }

    /** Ordering only, as with every other source; {@link MetricDriftSource} says the same. */
    static String gateSentence(FindingRow finding) {
        if (finding.humanVerdictAt() != null) {
            return "A human ruled this a real deviation.";
        }
        return "A triage run audited this claim and found it sound.";
    }

    /** The finding's own asserted severity, when the classifier wrote one; the unknown fallback
     *  otherwise. Ordering only, exactly as every other source's severity is. */
    private static double severity(FindingRow finding) {
        Double asserted = finding.severity();
        return asserted == null ? UNKNOWN_SEVERITY : Math.clamp(asserted, 0.0, 1.0);
    }
}
