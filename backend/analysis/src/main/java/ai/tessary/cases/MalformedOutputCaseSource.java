// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.classifier.malformed.MalformedOutputRateService;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shapes a call site whose schema-failure rate survived triage into a case, from the CUSUM
 * {@link MalformedOutputRateService} replays through tool_error's engine. Same gate as tool error. One case per call
 * site, since a schema and its fix live there alone.
 */
@Component
public class MalformedOutputCaseSource implements CaseSource {

    @Override
    public boolean owns(String classifierKey) {
        return BuiltInDetector.Kind.MALFORMED_OUTPUT.equals(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        // The basis needs only the rate, so the witness list stays empty.
        RateDetail read = MalformedOutputEvidence.rateDetail(finding, List.of());
        String callSite = finding.nativeCauseKey();
        CaseKey key = new CaseKey(
                CaseRow.Detector.MALFORMED_OUTPUT,
                CaseRow.SubjectKind.CALL_SITE,
                callSite,
                MalformedOutputEvidence.MEASURE);
        return new CaseDetection(
                key,
                callSite,
                finding.callSiteId(),
                finding.id(),
                // The same sentence the Classifiers page renders.
                FindingTitle.of(finding),
                basis(finding, read),
                severity(finding),
                onset(finding),
                read.curRate(),
                read.refRate(),
                read.deltaPp());
    }

    private static String basis(FindingRow finding, RateDetail read) {
        return String.format(
                Locale.ROOT,
                "%d of the %d outputs since %s failed their schema. Fitted rate %s over %d calls.",
                read.failuresCur(),
                read.nCur(),
                finding.onsetAt(),
                FindingTitle.pct(read.refRate()),
                read.nRef());
    }

    /** Delegates to tool_error's reader so two rate detectors on one engine never disagree about severity. */
    private static double severity(FindingRow finding) {
        double criticality = finding.payload().path("criticality").asDouble(Double.NaN);
        return Double.isNaN(criticality) ? 0.0 : ToolErrorEvidence.severityOf(criticality);
    }

    private static @Nullable Instant onset(FindingRow finding) {
        try {
            return Instant.parse(finding.onsetAt());
        } catch (Exception e) {
            return null;
        }
    }
}
