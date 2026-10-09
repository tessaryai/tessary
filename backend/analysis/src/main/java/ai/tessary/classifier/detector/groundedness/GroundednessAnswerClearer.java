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
 * What a {@code false_alarm} resolve does to the answers a groundedness case cites: their flags are cleared.
 *
 * <p>Every span-grain witness row on every finding the case holds names an answer the replay counted as
 * flagged, and the witnesses are all of them, not a sample. The trace-grain witness rows beside them name the
 * same traces, and the {@code member} rows are every trace scored, most of them clean, so neither is read here.
 * Clearing an answer's detection row ({@code cleared_at}) makes the next replay count its trace clean, unless
 * another flag in it still stands. {@code groundedness_assessment} is never touched: a cleared trace is still a
 * trial, it just stops being a failure.
 */
@Component
public class GroundednessAnswerClearer {

    /** Answers cleared per statement. */
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

    /**
     * Clear the flag on every flagged answer cited by a groundedness finding of case {@code caseId}.
     *
     * @return how many distinct answers the case cites, cleared now or already
     */
    public int clear(String projectId, String caseId, String now) {
        Map<String, String> classifierByFinding = new LinkedHashMap<>();
        for (FindingRow f : findings.listByCase(projectId, caseId)) {
            if (FindingRow.Cause.GROUNDEDNESS_RATE.equals(f.causeKind())
                    && FindingRow.SubjectKind.CLASSIFIER.equals(f.subjectKind())) {
                classifierByFinding.put(f.id(), f.subjectId());
            }
        }
        if (classifierByFinding.isEmpty()) return 0;

        // Walk findings in the case's order, not the returned map's, so the cleared list is deterministic.
        Map<String, List<FindingEvidenceRow>> rowsByFinding =
                evidence.listByFindings(projectId, new ArrayList<>(classifierByFinding.keySet()));
        Map<String, Set<SpanKey>> answersByClassifier = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : classifierByFinding.entrySet()) {
            for (FindingEvidenceRow row : rowsByFinding.getOrDefault(f.getKey(), List.of())) {
                // Only a witness at span grain is a flagged answer; members and trace witnesses are not.
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
            // In chunks: an uncapped witness set can outgrow what one statement's bind list holds.
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
