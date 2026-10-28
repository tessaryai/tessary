// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.frustration.FrustrationEvidence;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Shapes a call site whose rate rose above its learned rate into a case, for the three per-call-site rate
 * detectors that replay through tool_error's CUSUM: malformed output, groundedness and frustration.
 *
 * <p>Malformed output and groundedness reach here only once triage ruled the claim sound or a human pressed
 * <em>Real deviation</em>; a groundedness model's flags include false alarms, so a rise in them is audited before
 * it pages anyone. {@code FrustrationRateService} rules each spell's finding positive when it files it and opens
 * the case in the same transaction: the numbers on the finding are the claim, and there is no Layer-2 lane for it.
 *
 * <p>One case per call site, the findings' own scope. Severity is tool_error's criticality squash, so these cases
 * and tool_error's rank on one scale.
 */
@Component
public class RateCaseSource implements CaseSource {

    private record Rate(
            String detector,
            String measure,
            Function<FindingRow, RateDetail> reader,
            String sample,
            String flagged,
            String fit,
            String refSample) {}

    private static final String BASIS = "%d of the %d %s since %s %s. %s rate %s over %d %s.";

    private static final Map<String, Rate> RATES = Map.of(
            BuiltInDetector.Kind.MALFORMED_OUTPUT,
            new Rate(
                    CaseRow.Detector.MALFORMED_OUTPUT,
                    MalformedOutputEvidence.MEASURE,
                    // The basis needs the rate, never a specific failing output, so no witnesses are threaded.
                    finding -> MalformedOutputEvidence.rateDetail(finding, List.of()),
                    "outputs",
                    "failed their schema",
                    "Fitted",
                    "calls"),
            BuiltInDetector.Kind.GROUNDEDNESS,
            new Rate(
                    CaseRow.Detector.GROUNDEDNESS,
                    GroundednessEvidence.MEASURE,
                    GroundednessEvidence::rateDetail,
                    "traces",
                    "had an answer flagged",
                    "Learned",
                    "traces"),
            BuiltInDetector.Kind.FRUSTRATION,
            new Rate(
                    CaseRow.Detector.FRUSTRATION,
                    FrustrationEvidence.MEASURE,
                    FrustrationEvidence::rateDetail,
                    "session-hours",
                    "were frustrated with the agent",
                    "Learned",
                    "session-hours"));

    @Override
    public boolean owns(String classifierKey) {
        return RATES.containsKey(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        Rate rate = Objects.requireNonNull(RATES.get(finding.classifierKey()), finding.classifierKey());
        RateDetail read = rate.reader().apply(finding);
        String callSite = finding.nativeCauseKey();
        return new CaseDetection(
                new CaseKey(rate.detector(), CaseRow.SubjectKind.CALL_SITE, callSite, rate.measure()),
                callSite,
                finding.callSiteId(),
                finding.id(),
                FindingTitle.of(finding),
                String.format(
                        Locale.ROOT,
                        BASIS,
                        read.failuresCur(),
                        read.nCur(),
                        rate.sample(),
                        finding.onsetAt(),
                        rate.flagged(),
                        rate.fit(),
                        FindingTitle.pct(read.refRate()),
                        read.nRef(),
                        rate.refSample()),
                severity(finding),
                CaseSource.onset(finding),
                read.curRate(),
                read.refRate(),
                read.deltaPp());
    }

    /** Zero for a payload from before criticality was written, so it sorts last rather than borrowing a rank. */
    private static double severity(FindingRow finding) {
        double criticality = finding.payload().path("criticality").asDouble(Double.NaN);
        return Double.isNaN(criticality) ? 0.0 : ToolErrorEvidence.severityOf(criticality);
    }
}
