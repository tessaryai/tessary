// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
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
 * What a {@code false_alarm} resolve does to the conversations a frustration case cites: their flags are cleared.
 *
 * <p>Every session-grain evidence row on every finding the case holds names a conversation the replay counted as
 * frustrated. Clearing its detection rows ({@code cleared_at}) makes the next replay count it calm, and lets the
 * sweep send its later turns again, since suppression reads only uncleared rows. Turns scored before the clear are
 * not re-scored, and {@code frustration_assessment} is never touched: a cleared conversation is still a trial, it
 * just stops being a failure. There is no per-conversation verb; a reader who resolves a case with several spells
 * clears the conversations of all of them.
 */
@Component
public class FrustrationSessionClearer {

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ClassifierDetectionWriteRepository detections;

    public FrustrationSessionClearer(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ClassifierDetectionWriteRepository detections) {
        this.findings = findings;
        this.evidence = evidence;
        this.detections = detections;
    }

    /**
     * Clear the flag on every conversation cited by a frustration finding of case {@code caseId}.
     *
     * @return how many distinct conversations the case cites, cleared now or already
     */
    public int clear(String projectId, String caseId, String now) {
        Map<String, String> classifierByFinding = new LinkedHashMap<>();
        for (FindingRow f : findings.listByCase(projectId, caseId)) {
            if (FindingRow.Cause.FRUSTRATION_RATE.equals(f.causeKind())
                    && FindingRow.SubjectKind.CLASSIFIER.equals(f.subjectKind())) {
                classifierByFinding.put(f.id(), f.subjectId());
            }
        }
        if (classifierByFinding.isEmpty()) return 0;

        // Walk findings in the case's order, not the returned map's, so the cleared list is deterministic.
        Map<String, List<FindingEvidenceRow>> rowsByFinding =
                evidence.listByFindings(projectId, new ArrayList<>(classifierByFinding.keySet()));
        Map<String, Set<String>> sessionsByClassifier = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : classifierByFinding.entrySet()) {
            for (FindingEvidenceRow row : rowsByFinding.getOrDefault(f.getKey(), List.of())) {
                if (row.sessionId() == null) continue;
                sessionsByClassifier
                        .computeIfAbsent(f.getValue(), k -> new LinkedHashSet<>())
                        .add(row.sessionId());
            }
        }

        int conversations = 0;
        for (Map.Entry<String, Set<String>> e : sessionsByClassifier.entrySet()) {
            List<String> sessions = List.copyOf(e.getValue());
            detections.clearSessions(BuiltInDetector.Kind.FRUSTRATION, projectId, e.getKey(), sessions, now);
            conversations += sessions.size();
        }
        return conversations;
    }
}
