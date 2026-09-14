// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.secretleak;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.SecretLeakKeySummary;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.SecretLeakSummary;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.SecretLeakWitness;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.SpanKey;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakDetail;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakKeyView;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakLeakView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * "When it leaked": a secret-leak finding's own timeline detail, the only DB-backed half of {@link
 * SecretLeakEvidence} — the per-key breakdown and the leak-level detail have no home in the finding's
 * own payload, the same reason {@code MalformedOutputDetailService} exists for its classifier.
 */
@Service
public class SecretLeakDetailService {

    private final ClassifierDetectionWriteRepository detections;
    private final FindingEvidenceRepository evidence;

    public SecretLeakDetailService(ClassifierDetectionWriteRepository detections, FindingEvidenceRepository evidence) {
        this.detections = detections;
        this.evidence = evidence;
    }

    /** Null for any finding that is not a live {@code secret_leak} facet. */
    public @Nullable SecretLeakDetail detail(FindingRow finding) {
        String callSiteId = finding.callSiteId();
        if (!FindingRow.Cause.ARMED_WINDOW.equals(finding.causeKind())
                || !BuiltInDetector.Kind.SECRET_LEAK.equals(finding.classifierKey())) {
            return null;
        }
        String rule = finding.nativeCauseKey();
        Instant onset = onsetOf(finding);

        SecretLeakSummary summary = detections.secretLeakSummary(
                BuiltInDetector.Kind.SECRET_LEAK, finding.projectId(), finding.subjectId(), rule, callSiteId, onset);
        List<SecretLeakKeySummary> keys = detections.secretLeakKeys(
                BuiltInDetector.Kind.SECRET_LEAK, finding.projectId(), finding.subjectId(), rule, callSiteId, onset);
        List<SecretLeakWitness> witnesses = detections.secretLeakWitnesses(
                BuiltInDetector.Kind.SECRET_LEAK, finding.projectId(), finding.subjectId(), witnessSpans(finding));

        return new SecretLeakDetail(
                rule,
                summary != null && summary.anyHigh() ? "high" : "low",
                summary == null ? finding.sampleCount() : summary.leakCount(),
                summary == null ? 0 : summary.traceCount(),
                summary == null ? null : str(summary.firstAt()),
                summary == null ? null : str(summary.lastAt()),
                keys.stream()
                        .map(k -> new SecretLeakKeyView(
                                k.masked(), k.leaks(), k.traces(), str(k.lastAt()), k.storedRaw()))
                        .toList(),
                witnesses.stream()
                        .map(w -> new SecretLeakLeakView(
                                str(w.at()),
                                w.masked() == null ? "unknown" : w.masked(),
                                w.stored() == null ? "unknown" : w.stored(),
                                w.traceId(),
                                w.spanId()))
                        .toList());
    }

    /** Every WITNESS ref's (trace, span) pair, the finding's own bounded population. */
    private List<SpanKey> witnessSpans(FindingRow finding) {
        List<SpanKey> out = new ArrayList<>();
        for (FindingEvidenceRow row : evidence.listByFinding(finding.projectId(), finding.id())) {
            if (FindingEvidenceRow.Role.WITNESS.equals(row.role()) && row.traceId() != null && row.spanId() != null) {
                out.add(new SpanKey(row.traceId(), row.spanId()));
            }
        }
        return out;
    }

    /** {@code onset_at}, or the epoch when it fails to parse — a facet with no history rather than a 500. */
    private static Instant onsetOf(FindingRow finding) {
        try {
            return Instant.parse(finding.onsetAt());
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private static @Nullable String str(@Nullable Instant at) {
        return at == null ? null : at.toString();
    }
}
