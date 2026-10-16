// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricFindingEvidence.Read;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shapes a triaged metric-drift finding into a case. {@link CaseOpener} has already qualified the finding, so there is
 * no gate here. Values come from the finding's own evidence via {@link MetricFindingEvidence#read}; an unreadable blob
 * still opens the case, without invented numbers.
 */
@Component
public class MetricDriftSource implements CaseSource {

    private static final Logger log = LoggerFactory.getLogger(MetricDriftSource.class);

    private static final Set<String> METRIC_CLASSIFIERS =
            Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);

    /** Saturates at a 3x move; ordering only. */
    private static final double SEVERITY_SATURATION = Math.log(3);

    /** Mid-list deliberately: sorting an unreadable blob to the bottom would hide a confirmed regression. */
    private static final double UNKNOWN_SEVERITY = 0.5;

    @Override
    public boolean owns(String classifierKey) {
        return METRIC_CLASSIFIERS.contains(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        Read read = MetricFindingEvidence.read(finding.payloadJson());
        if (read == null) {
            log.warn(
                    "metric-drift finding {} has unreadable evidence — opening its case without numbers", finding.id());
        }
        // One case per cause, matching one finding row per cause: the cause key carries the
        // measure, bucket, direction, and reference, so a shift against last week and a creep
        // since the last deploy stay two separate cases.
        CaseKey key = new CaseKey(
                CaseRow.Detector.METRIC_DRIFT,
                CaseRow.SubjectKind.METRIC_BASELINE,
                finding.nativeCauseKey(),
                measureOf(finding));
        return new CaseDetection(
                key,
                read != null ? read.bucketKey() : finding.nativeCauseKey(),
                finding.callSiteId(),
                finding.id(),
                // The same sentence the Classifiers page renders on the finding this came from.
                FindingTitle.of(finding),
                basis(finding, read),
                severity(read),
                CaseSource.onset(finding),
                // The medians, in the measure's raw units: milliseconds for duration, dollars for cost.
                read == null ? null : boxed(read.curP50()),
                read == null ? null : boxed(read.refP50()),
                delta(read));
    }

    /**
     * Read off the cause key rather than the evidence blob, so a bad jsonb column can't split one regression into two
     * cases.
     */
    private static String measureOf(FindingRow finding) {
        String causeKey = finding.nativeCauseKey();
        int colon = causeKey.indexOf(':');
        return colon > 0 ? causeKey.substring(0, colon) : finding.causeKind();
    }

    /**
     * In this detector's own terms; other detectors use different thresholds, so a shared scale would misrepresent
     * them.
     */
    private static String basis(FindingRow finding, @Nullable Read read) {
        String gate = GenericFindingCaseSource.gateSentence(finding);
        if (read == null) {
            return gate + " Its measured shift could not be read back from the finding — see the finding "
                    + "itself on the Classifiers page.";
        }
        return String.format(
                Locale.ROOT,
                "%s Distribution shift against %s — W₁ %.2f on logs over %d %s. Effect size, not "
                        + "significance: the bar is this population's own recent past, at any absolute level.",
                gate,
                referenceWords(read.reference()),
                Math.abs(read.w1Log()),
                read.nCur(),
                sampleWord(read.measure()));
    }

    /**
     * The wire value ({@code pinned}/{@code previous}) is persisted in every {@code cause_key}, so it stays as-is; only
     * the sentence changes.
     */
    private static String referenceWords(String reference) {
        return Reference.PINNED.wire().equals(reference) ? "the window pinned at the last deploy" : "its recent normal";
    }

    private static String sampleWord(String measure) {
        return Measure.TOOL_DURATION.equals(measure) ? "calls" : "turns";
    }

    /** Ordering only, and never rendered as a number: the honest magnitude is the ratio in the title. */
    private static double severity(@Nullable Read read) {
        if (read == null) return UNKNOWN_SEVERITY;
        return Math.min(1, Math.abs(read.w1Log()) / SEVERITY_SATURATION);
    }

    private static @Nullable Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }

    private static @Nullable Double delta(@Nullable Read read) {
        if (read == null || read.refP50().isEmpty() || read.curP50().isEmpty()) return null;
        return read.curP50().getAsDouble() - read.refP50().getAsDouble();
    }
}
