// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.frustration.FrustrationEvidence;
import ai.tessary.classifier.frustration.FrustrationRateService;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shapes a call site whose frustrated-session share rose above its learned rate into a case, from the CUSUM
 * {@link FrustrationRateService} replays. No triage gate: the service rules each finding positive and calls
 * {@link CaseOpener} itself, since the numbers settle the claim. Severity uses the shared rate squash.
 */
@Component
public class FrustrationCaseSource implements CaseSource {

    @Override
    public boolean owns(String classifierKey) {
        return BuiltInDetector.Kind.FRUSTRATION.equals(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        RateDetail read = FrustrationEvidence.rateDetail(finding);
        String callSite = finding.nativeCauseKey();
        CaseKey key = new CaseKey(
                CaseRow.Detector.FRUSTRATION, CaseRow.SubjectKind.CALL_SITE, callSite, FrustrationEvidence.MEASURE);
        return new CaseDetection(
                key,
                callSite,
                finding.callSiteId(),
                finding.id(),
                FindingTitle.of(finding),
                basis(finding, read),
                FrustrationEvidence.severity(finding),
                onset(finding),
                read.curRate(),
                read.refRate(),
                read.deltaPp());
    }

    private static String basis(FindingRow finding, RateDetail read) {
        return String.format(
                Locale.ROOT,
                "%d of the %d sessions since %s were frustrated with the agent. Learned rate %s over %d" + " sessions.",
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
