// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricFindingEvidence.Read;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shapes a triaged metric-drift finding into a case: a bucket's duration or cost distribution sitting
 * measurably away from its own earlier baseline.
 *
 * <p>{@link CaseOpener} only calls {@link #shape} once a finding's ruling has already qualified it — a
 * triage run's positive, or a human's "Real deviation" — so this class has no gate of its own to apply.
 * {@link #gateSentence} still records which of the two authorities ruled, since that is part of the
 * case's own account of itself. Title, basis, and values are read back from the finding's own evidence
 * blob via {@link MetricFindingEvidence#read}, so a case can't disagree with the finding it came from;
 * when that blob is unreadable the case still opens, without inventing numbers.
 */
@Component
public class MetricDriftSource implements CaseSource {

    private static final Logger log = LoggerFactory.getLogger(MetricDriftSource.class);

    /** The classifiers whose findings this source shapes. */
    private static final Set<String> METRIC_CLASSIFIERS =
            Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);

    /**
     * Severity saturates at {@code ln(3)}, a 3x move: past a tripling, "more" stops changing triage
     * order. Ordering only; the real magnitude lives in {@link CaseDetection#basis}.
     */
    private static final double SEVERITY_SATURATION = Math.log(3);

    /**
     * Severity for a finding whose evidence can't be read. Mid-list deliberately: sorting an
     * unreadable blob to the bottom would hide a confirmed regression as effectively as dropping it.
     */
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
                onset(finding),
                // The medians, in the measure's raw units: milliseconds for duration, dollars for cost.
                read == null ? null : boxed(read.curP50()),
                read == null ? null : boxed(read.refP50()),
                delta(read));
    }

    /**
     * The measure that moved, read off the cause key's first segment
     * ({@code <measure>:<bucket>:<direction>:<reference>}) rather than the evidence blob, so a bad
     * jsonb column can't split one regression into two cases.
     */
    private static String measureOf(FindingRow finding) {
        String causeKey = finding.nativeCauseKey();
        int colon = causeKey.indexOf(':');
        return colon > 0 ? causeKey.substring(0, colon) : finding.causeKind();
    }

    /**
     * Why this crossed its own bar, in this detector's own terms. Other detectors on the same
     * ranked list use different thresholds, so normalizing onto a shared scale would misrepresent
     * the comparison. Also names the gate, since the gate is half of why this is a case at all.
     */
    private static String basis(FindingRow finding, @Nullable Read read) {
        String gate = gateSentence(finding);
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
     * Who confirmed this and on what authority: a human pressed "Real deviation" directly, or a
     * triage run audited the claim and found it sound. Spelled out on the case since a reader
     * deciding whether to page someone needs to know which one they're looking at.
     */
    private static String gateSentence(FindingRow finding) {
        if (finding.humanVerdictAt() != null) {
            return "A human ruled this a real deviation.";
        }
        return "A triage run audited this claim and found it sound.";
    }

    /**
     * The reference in words. The wire value ({@code pinned}/{@code previous}) is a persisted
     * string in every {@code cause_key} ever written, so it stays as-is even as what it names
     * evolves; only the human-readable sentence changes.
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

    /** An {@code OptionalDouble} as the nullable {@code Double} the detection record carries. */
    private static @Nullable Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }

    /** How far the median moved, in the measure's own units, or null when either side is unknown. */
    private static @Nullable Double delta(@Nullable Read read) {
        if (read == null || read.refP50().isEmpty() || read.curP50().isEmpty()) return null;
        return read.curP50().getAsDouble() - read.refP50().getAsDouble();
    }

    /**
     * When the current spell began: the finding's own onset, moved only across an observed recovery
     * (see {@code FindingRepository}). A case's own onset is stamped once, at open, and never moves —
     * this is only ever read on the finding that opens or joins a case.
     *
     * <p>Trade-off: under a backfill this isn't exact; the finding's own evidence carries the true
     * window bounds for a reader who needs them.
     */
    private static @Nullable Instant onset(FindingRow finding) {
        return parse(finding.onsetAt());
    }

    /** A null onset means the spell is unbracketed, rather than substituting {@code now}. */
    private static @Nullable Instant parse(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
