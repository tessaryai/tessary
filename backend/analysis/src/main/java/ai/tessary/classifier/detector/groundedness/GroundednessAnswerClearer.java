// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.SpanKey;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A {@code false_alarm} resolve clears the flags of every answer a groundedness case cites. Span-grain witness rows are
 * every flagged answer, not a sample; trace witnesses and members are not read. The next replay counts a trace clean
 * unless another flag in it stands. {@code groundedness_assessment} is never touched.
 */
@Component
public class GroundednessAnswerClearer {

    static final int CLEAR_CHUNK = 1_000;

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ClassifierDetectionWriteRepository detections;

    public GroundednessAnswerClearer(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierDetectionWriteRepository detections) {
        this.findings = findings;
        this.evidence = evidence;
        this.detections = detections;
    }

    /** @return how many distinct answers the case cites, cleared now or already */
    public int clear(String projectId, String caseId, String now) {
        Map<String, String> classifierByFinding = new LinkedHashMap<>();
        for (FindingRow f : findings.listByCase(projectId, caseId)) {
            if (FindingRow.Cause.GROUNDEDNESS_RATE.equals(f.causeKind())
                    && FindingRow.SubjectKind.CLASSIFIER.equals(f.subjectKind())) {
                classifierByFinding.put(f.id(), f.subjectId());
            }
        }
        if (classifierByFinding.isEmpty()) return 0;

        // In the case's order, so the cleared list is deterministic.
        Map<String, List<FindingEvidenceRow>> rowsByFinding =
                evidence.listByFindings(projectId, new ArrayList<>(classifierByFinding.keySet()));
        Map<String, Set<SpanKey>> answersByClassifier = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : classifierByFinding.entrySet()) {
            for (FindingEvidenceRow row : rowsByFinding.getOrDefault(f.getKey(), List.of())) {
                // Only a span-grain witness is a flagged answer.
                if (row.traceId() != null
                        && row.spanId() != null
                        && FindingEvidenceRow.Role.WITNESS.equals(row.role())) {
                    answersByClassifier
                            .computeIfAbsent(f.getValue(), k -> new LinkedHashSet<>())
                            .add(new SpanKey(row.traceId(), row.spanId()));
                }
            }
        }

        int answers = 0;
        for (Map.Entry<String, Set<SpanKey>> e : answersByClassifier.entrySet()) {
            List<SpanKey> cited = List.copyOf(e.getValue());
            // Chunked: an uncapped witness set can outgrow one statement's bind list.
            for (int from = 0; from < cited.size(); from += CLEAR_CHUNK) {
                detections.clearSpans(
                        BuiltInDetector.Kind.GROUNDEDNESS,
                        projectId,
                        e.getKey(),
                        cited.subList(from, Math.min(from + CLEAR_CHUNK, cited.size())),
                        now);
            }
            answers += cited.size();
        }
        return answers;
    }
}
