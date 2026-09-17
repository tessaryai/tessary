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
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
        return detail(List.of(finding));
    }

    /**
     * A secret-leak case's detail, read across every finding the case holds, newest first. The rule, bar
     * and window come from the newest finding, but the keys to rotate and when it leaked span them all: the
     * summary and keys count from the earliest onset, and the witnesses are every finding's, so a case that
     * joined findings from several windows still lists the key its first window leaked.
     *
     * @return null when the newest finding is not a live {@code secret_leak} facet
     */
    public @Nullable SecretLeakDetail detail(List<FindingRow> caseFindings) {
        if (caseFindings.isEmpty()) return null;
        FindingRow finding = caseFindings.get(0);
        String callSiteId = finding.callSiteId();
        if (!FindingRow.Cause.ARMED_WINDOW.equals(finding.causeKind())
                || !BuiltInDetector.Kind.SECRET_LEAK.equals(finding.classifierKey())) {
            return null;
        }
        List<FindingRow> facet = caseFindings.stream()
                .filter(f -> FindingRow.Cause.ARMED_WINDOW.equals(f.causeKind())
                        && BuiltInDetector.Kind.SECRET_LEAK.equals(f.classifierKey()))
                .toList();
        String rule = finding.nativeCauseKey();
        Instant onset = earliestOnset(facet);
        JsonNode payload = finding.payload();

        SecretLeakSummary summary = detections.secretLeakSummary(
                BuiltInDetector.Kind.SECRET_LEAK, finding.projectId(), finding.subjectId(), rule, callSiteId, onset);
        List<SecretLeakKeySummary> keys = detections.secretLeakKeys(
                BuiltInDetector.Kind.SECRET_LEAK, finding.projectId(), finding.subjectId(), rule, callSiteId, onset);
        List<SecretLeakWitness> witnesses = detections.secretLeakWitnesses(
                BuiltInDetector.Kind.SECRET_LEAK, finding.projectId(), finding.subjectId(), witnessSpans(facet));

        return new SecretLeakDetail(
                rule,
                facet.stream().anyMatch(FindingRow::highConfidence)
                        ? FindingRow.Confidence.HIGH
                        : FindingRow.Confidence.LOW,
                summary == null
                        ? facet.stream().mapToLong(FindingRow::sampleCount).sum()
                        : summary.leakCount(),
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
                        .toList(),
                payload.path("basis").asText("event_count"),
                payload.path("threshold").asLong(0),
                payload.path("window_seconds").asLong(0),
                text(payload.path("window_start")),
                text(payload.path("window_end")));
    }

    private static @Nullable String text(JsonNode node) {
        String s = node.asText("");
        return s.isEmpty() ? null : s;
    }

    /** Every WITNESS ref's (trace, span) pair across the findings, once each: their bounded population. */
    private List<SpanKey> witnessSpans(List<FindingRow> facet) {
        Set<SpanKey> out = new LinkedHashSet<>();
        for (FindingRow finding : facet) {
            for (FindingEvidenceRow row : evidence.listByFinding(finding.projectId(), finding.id())) {
                if (FindingEvidenceRow.Role.WITNESS.equals(row.role())
                        && row.traceId() != null
                        && row.spanId() != null) {
                    out.add(new SpanKey(row.traceId(), row.spanId()));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The earliest {@code onset_at} that parses, or the epoch when none does: a facet with no history
     * rather than a 500.
     */
    private static Instant earliestOnset(List<FindingRow> facet) {
        return facet.stream()
                .map(SecretLeakDetailService::onsetOrNull)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
    }

    private static @Nullable Instant onsetOrNull(FindingRow finding) {
        try {
            return Instant.parse(finding.onsetAt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String str(@Nullable Instant at) {
        return at == null ? null : at.toString();
    }
}
