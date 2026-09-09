// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRepository.SurvivalGate;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.Read;
import ai.tessary.classifier.toolerror.ToolErrorService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tool-error rate shifts, from the CUSUM in {@link ToolErrorService}. Design contract:
 * {@code classifiers/tool_error/PROGRAM.md} §6.1 and §8.2.
 *
 * <p><b>The gate is the same one metric drift uses</b>, and shared in code rather than restated: a case
 * opens only once triage has ruled the shift's claim sound or a human has pressed <em>Real deviation</em>
 * ({@code listLiveByCause}), so every Triage row means the same thing whichever detector found it.
 *
 * <p><b>This source only lists.</b> The recompute it used to run first — replaying the hourly aggregate
 * to bring every tool's spell up to date — now belongs to the classifier's own sweep, where every other
 * classifier's does. It was here because tool errors had no sweep at all, and the cost of that was a
 * read path that wrote: a case reconciler pass could not tell you what a project's findings were without
 * changing them, findings only advanced while someone was looking at Triage, and the classifier's rail
 * on the Classifiers page showed whatever the last case pass happened to leave. All four sources are now
 * the same shape — one query over {@code finding}, gated per §3.
 *
 * <h2>Why one case per tool and not per cause</h2>
 *
 * <p>The finding's cause key carries the direction, because a rise and a fall are different things to
 * explain. The {@link CaseKey} deliberately does not. If it did, a tool whose rate rose (opening a case)
 * and later fell back would alarm on the improvement arm under a different key and open a SECOND case,
 * celebrating the recovery of the first. Both directions therefore collapse onto one live case per tool.
 */
@Component
public class ToolErrorCaseSource implements CaseSource {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorCaseSource.class);

    /** Bound on one pass's live set, mirroring the other sources. */
    private static final int LIVE_SET_CAP = 200;

    /** The classifier whose findings open a tool-error case, named as the shared table's readers route. */
    private static final List<String> TOOL_ERROR_CLASSIFIERS = List.of(BuiltInDetector.Kind.TOOL_ERROR);

    /**
     * How long a confirmed shift may go unrefreshed before its case counts as recovered.
     *
     * <p>Owned by {@link ToolErrorService} rather than declared here, because the same number also
     * decides when the WRITE side starts a new spell. See its javadoc: a case that closed as recovered
     * and a finding whose onset had not moved is precisely the pairing that made a re-fire silent.
     */
    private static final Duration QUIET_WINDOW = ToolErrorService.QUIET_WINDOW;

    private final FindingRepository findings;

    public ToolErrorCaseSource(FindingRepository findings) {
        this.findings = findings;
    }

    @Override
    public String detector() {
        return CaseRow.Detector.TOOL_ERROR;
    }

    @Override
    public List<CaseDetection> detect(String projectId) {
        String seenSince = Instant.now().minus(QUIET_WINDOW).toString();
        List<CaseDetection> out = new ArrayList<>();
        for (FindingRow finding : findings.listSurvivingAnalysis(
                projectId, TOOL_ERROR_CLASSIFIERS, SurvivalGate.MACHINE_OR_HUMAN, seenSince, LIVE_SET_CAP)) {
            out.add(toDetection(finding));
        }
        return out;
    }

    private static CaseDetection toDetection(FindingRow finding) {
        Read read = ToolErrorEvidence.read(finding.payloadJson());
        if (read == null) {
            log.warn("tool-error finding {} has unreadable evidence — opening its case without numbers", finding.id());
        }
        String bucketKey = read != null ? read.bucketKey() : bucketFromCauseKey(finding.nativeCauseKey());
        CaseKey key = new CaseKey(
                CaseRow.Detector.TOOL_ERROR, CaseRow.SubjectKind.TOOL, bucketKey, ToolErrorEvidence.MEASURE);
        return new CaseDetection(
                key,
                ToolErrorEvidence.shortName(bucketKey),
                finding.callSiteId(),
                finding.id(),
                // The SAME sentence the Classifiers page renders on the finding this came from: a case
                // that re-worded it would leave a reader matching two names for one event.
                FindingTitle.of(finding),
                basis(finding, read),
                severity(read),
                // Passed through INCLUDING when null, never substituted with now: CaseLedger keys
                // reopen-vs-stay-closed on whether the onset moved, so a null advancing to a timestamp on
                // every pass would reopen a case a human just closed.
                onset(finding),
                read == null ? null : read.curRate(),
                read == null ? null : read.refRate(),
                read == null ? null : read.deltaPp());
    }

    private static String basis(FindingRow finding, @Nullable Read read) {
        if (read == null) return "A sustained change in this tool's failure rate, with unreadable evidence.";
        String patterns = read.patternCount() == 0
                ? ""
                : read.patternCount() == 1
                        ? " One error pattern accounts for it."
                        : String.format(Locale.ROOT, " Across %d error patterns.", read.patternCount());
        return String.format(
                Locale.ROOT,
                "Sustained change against this tool's own past failure rate, %s to %s (%+.2fpp).%s",
                FindingTitle.pct(read.refRate()),
                FindingTitle.pct(read.curRate()),
                read.deltaPp(),
                patterns);
    }

    /**
     * Ordering only, never rendered. Zero when the numbers are unreadable or predate criticality, so such
     * a case sorts last rather than claiming a rank it has no basis for.
     *
     * <p>Delegates the squash rather than repeating it. The old copy here recomputed Cohen's h inline from
     * the blob's rates, which meant two places could disagree about how a case ranks.
     */
    private static double severity(@Nullable Read read) {
        return read == null ? 0.0 : ToolErrorEvidence.severityOf(read.criticality());
    }

    private static @Nullable Instant onset(FindingRow finding) {
        try {
            return Instant.parse(finding.onsetAt());
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code tool_error_rate:tool:search_docs:up} → {@code tool:search_docs}. */
    private static String bucketFromCauseKey(String causeKey) {
        int first = causeKey.indexOf(':');
        int last = causeKey.lastIndexOf(':');
        return first >= 0 && last > first ? causeKey.substring(first + 1, last) : causeKey;
    }
}
