// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence;
import ai.tessary.classifier.detector.groundedness.GroundednessRateService;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shapes a call site whose flagged-answer share survived triage into a case, from the CUSUM
 * {@link GroundednessRateService} replays through tool_error's engine. Gated on triage or a human because the model's
 * flags include false alarms. Severity uses the shared rate squash so cases rank on one scale.
 */
@Component
public class GroundednessCaseSource implements CaseSource {

    @Override
    public boolean owns(String classifierKey) {
        return BuiltInDetector.Kind.GROUNDEDNESS.equals(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        RateDetail read = GroundednessEvidence.rateDetail(finding);
        String callSite = finding.nativeCauseKey();
        CaseKey key = new CaseKey(
                CaseRow.Detector.GROUNDEDNESS, CaseRow.SubjectKind.CALL_SITE, callSite, GroundednessEvidence.MEASURE);
        return new CaseDetection(
                key,
                callSite,
                finding.callSiteId(),
                finding.id(),
                // The same sentence the Classifiers page renders.
                FindingTitle.of(finding),
                basis(finding, read),
                GroundednessEvidence.severity(finding),
                onset(finding),
                read.curRate(),
                read.refRate(),
                read.deltaPp());
    }

    /** Labelled as flagged, since flags include false alarms. */
    private static String basis(FindingRow finding, RateDetail read) {
        return String.format(
                Locale.ROOT,
                "%d of the %d traces since %s had an answer flagged. Learned rate %s over %d traces.",
                read.failuresCur(),
                read.nCur(),
                finding.onsetAt(),
                FindingTitle.pct(read.refRate()),
                read.nRef());
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
