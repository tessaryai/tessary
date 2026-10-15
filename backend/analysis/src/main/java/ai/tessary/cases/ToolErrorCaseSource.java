// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.Read;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shapes a tool-error rate shift into a case ({@code tool-error.md} §6.1, §8.2), gated by {@link CaseOpener} like
 * metric drift. One case per tool, not per direction: otherwise a recovery would open a second case celebrating the
 * first.
 */
@Component
public class ToolErrorCaseSource implements CaseSource {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorCaseSource.class);

    @Override
    public boolean owns(String classifierKey) {
        return BuiltInDetector.Kind.TOOL_ERROR.equals(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
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
                // The same sentence the Classifiers page renders on the finding.
                FindingTitle.of(finding),
                basis(finding, read),
                severity(read),
                // Never substituted with now: a case's onset is stamped once and never moves.
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

    /** Ordering only. Zero when unreadable, so the case sorts last rather than claiming a rank. */
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
