// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRepository.SurvivalGate;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.classifier.malformed.MalformedOutputRateService;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Call sites whose declared-schema failure rate survived triage, from the CUSUM
 * {@link MalformedOutputRateService} replays through tool_error's engine.
 *
 * <p><b>Same gate as tool error</b>, and shared by name rather than restated: a case opens only once
 * triage has ruled the shift's claim sound or a human has pressed <em>Real deviation</em>
 * ({@code listSurvivingAnalysis}, {@link SurvivalGate#MACHINE_OR_HUMAN}), so a Triage row means the
 * same thing whichever of the two rate-shaped detectors found it.
 *
 * <p>One case per call site: {@code malformed_rate}'s subject is already scoped that way ({@code
 * CauseKey.malformedOutput}), and a call site's schema and its fix both live at that call site alone.
 */
@Component
public class MalformedOutputCaseSource implements CaseSource {

    /** Bound on one pass's live set, mirroring the other sources. */
    private static final int LIVE_SET_CAP = 200;

    private static final List<String> MALFORMED_OUTPUT_CLASSIFIERS = List.of(BuiltInDetector.Kind.MALFORMED_OUTPUT);

    /** Same spell-recovery horizon the write side uses, mirroring {@link ToolErrorCaseSource}'s own
     *  reuse of {@code ToolErrorService.QUIET_WINDOW}. */
    private static final Duration QUIET_WINDOW = MalformedOutputRateService.QUIET_WINDOW;

    private final FindingRepository findings;

    public MalformedOutputCaseSource(FindingRepository findings) {
        this.findings = findings;
    }

    @Override
    public String detector() {
        return CaseRow.Detector.MALFORMED_OUTPUT;
    }

    @Override
    public List<CaseDetection> detect(String projectId) {
        String seenSince = Instant.now().minus(QUIET_WINDOW).toString();
        List<CaseDetection> out = new ArrayList<>();
        for (FindingRow finding : findings.listSurvivingAnalysis(
                projectId, MALFORMED_OUTPUT_CLASSIFIERS, SurvivalGate.MACHINE_OR_HUMAN, seenSince, LIVE_SET_CAP)) {
            out.add(toDetection(finding));
        }
        return out;
    }

    private static CaseDetection toDetection(FindingRow finding) {
        // The case's basis only needs the rate, never a specific failing output, so the witness list
        // this reader would otherwise thread through stays empty — see MalformedOutputEvidence#rateDetail.
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
                // The same sentence the Classifiers page renders on the finding this came from.
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

    /** Ordering only, delegating the squash to tool_error's reader, exactly as {@link ToolErrorCaseSource}
     *  does: two rate detectors sharing one engine must not disagree about how their severity is computed. */
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
