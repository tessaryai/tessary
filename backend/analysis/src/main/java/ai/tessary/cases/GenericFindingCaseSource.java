// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shapes a qualifying finding into a case for every classifier the five dedicated sources
 * ({@link MetricDriftSource}, {@link ToolErrorCaseSource}, {@link MalformedOutputCaseSource},
 * {@link SecretLeakCaseSource}, {@link FrustrationCaseSource}) don't own: behaviour drift, SOP conformance, and any per-span classifier
 * an org authors and arms itself. None of these had a case source before decision 1 — behaviour drift's
 * findings never opened one at all, and an armed per-span classifier's only route to a case was a
 * human's <em>Real deviation</em> on a finding {@code ClassifierArming} filed.
 *
 * <p>Unlike the other five this source carries no evidence blob of its own shape to read numbers back
 * from — its findings span whatever the classifier itself measured (a novel gram, a rule violated, N
 * detections in a window) — so the case it shapes states the fact plainly rather than a fitted
 * before/after pair. {@link #gateSentence} still records which authority ruled, matching every other
 * source's own account of itself.
 */
@Component
public class GenericFindingCaseSource implements CaseSource {

    /** The classifiers the five dedicated sources already own; this source claims everything else. */
    private static final Set<String> DEDICATED = Set.of(
            BuiltInDetector.Kind.DURATION_DRIFT,
            BuiltInDetector.Kind.COST_DRIFT,
            BuiltInDetector.Kind.TOOL_ERROR,
            BuiltInDetector.Kind.MALFORMED_OUTPUT,
            BuiltInDetector.Kind.SECRET_LEAK,
            BuiltInDetector.Kind.FRUSTRATION);

    /** Severity for a cause this source cannot grade a magnitude for. Mid-list deliberately, same
     *  reasoning as the other sources' own unreadable-evidence fallback. */
    private static final double UNKNOWN_SEVERITY = 0.5;

    @Override
    public String detector() {
        // Never read: shape() picks the detector per finding, since this one source spans several.
        return CaseRow.Detector.CLASSIFIER;
    }

    @Override
    public boolean owns(String classifierKey) {
        return !DEDICATED.contains(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        CaseKey key =
                new CaseKey(detectorOf(finding), subjectKindOf(finding), finding.causeKey(), finding.classifierKey());
        return new CaseDetection(
                key,
                finding.subjectLabel() != null ? finding.subjectLabel() : finding.nativeCauseKey(),
                finding.callSiteId(),
                finding.id(),
                FindingTitle.of(finding),
                basis(finding),
                severity(finding),
                onset(finding),
                null,
                null,
                null);
    }

    private static String detectorOf(FindingRow finding) {
        if (BuiltInDetector.Kind.BEHAVIOR_DRIFT.equals(finding.classifierKey())) {
            return CaseRow.Detector.BEHAVIOR_DRIFT;
        }
        if (BuiltInDetector.Kind.SOP_CONFORMANCE.equals(finding.classifierKey())) {
            return CaseRow.Detector.SOP_CONFORMANCE;
        }
        return CaseRow.Detector.CLASSIFIER;
    }

    private static String subjectKindOf(FindingRow finding) {
        return BuiltInDetector.Kind.BEHAVIOR_DRIFT.equals(finding.classifierKey())
                ? CaseRow.SubjectKind.BEHAVIOR_PROFILE
                : CaseRow.SubjectKind.CLASSIFIER;
    }

    /** Why this crossed its own bar, in this detector's own terms, prefixed by which authority ruled —
     *  the finding's own {@link FindingRow#basis()} when the detector wrote one, a generic account
     *  otherwise. */
    private static String basis(FindingRow finding) {
        String gate = gateSentence(finding);
        String detail = finding.basis();
        return detail == null || detail.isBlank() ? gate : gate + " " + detail;
    }

    /** Who confirmed this and on what authority — see the sibling sources' identically-named helper. */
    private static String gateSentence(FindingRow finding) {
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

    private static @Nullable Instant onset(FindingRow finding) {
        String onset = finding.onsetAt();
        if (onset == null || onset.isBlank()) return null;
        try {
            return Instant.parse(onset);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
