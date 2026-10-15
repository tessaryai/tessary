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
 * A {@code false_alarm} resolve clears the flags of every session a frustration case cites. The witness rows are every
 * frustrated session on the finding's call site, not a sample. Clearing makes the next replay count them calm and lets
 * the sweep send later turns again; earlier turns are not rescored and {@code frustration_assessment} is never touched.
 * Only the finding's call site is cleared.
 */
@Component
public class FrustrationSessionClearer {

    static final int CLEAR_CHUNK = 1_000;

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

    /** @return how many distinct sessions the case cites, cleared now or already */
    public int clear(String projectId, String caseId, String now) {
        Map<String, Scope> scopeByFinding = new LinkedHashMap<>();
        for (FindingRow f : findings.listByCase(projectId, caseId)) {
            if (FindingRow.Cause.FRUSTRATION_RATE.equals(f.causeKind())
                    && FindingRow.SubjectKind.CLASSIFIER.equals(f.subjectKind())) {
                scopeByFinding.put(f.id(), new Scope(f.subjectId(), f.nativeCauseKey()));
            }
        }
        if (scopeByFinding.isEmpty()) return 0;

        // In the case's order, so the cleared list is deterministic.
        Map<String, List<FindingEvidenceRow>> rowsByFinding =
                evidence.listByFindings(projectId, new ArrayList<>(scopeByFinding.keySet()));
        Map<Scope, Set<String>> sessionsByScope = new LinkedHashMap<>();
        for (Map.Entry<String, Scope> f : scopeByFinding.entrySet()) {
            for (FindingEvidenceRow row : rowsByFinding.getOrDefault(f.getKey(), List.of())) {
                if (row.sessionId() == null || !FindingEvidenceRow.Role.WITNESS.equals(row.role())) continue;
                sessionsByScope
                        .computeIfAbsent(f.getValue(), k -> new LinkedHashSet<>())
                        .add(row.sessionId());
            }
        }

        int sessions = 0;
        for (Map.Entry<Scope, Set<String>> e : sessionsByScope.entrySet()) {
            List<String> cited = List.copyOf(e.getValue());
            // Chunked: an uncapped witness set can outgrow one statement's bind list.
            for (int from = 0; from < cited.size(); from += CLEAR_CHUNK) {
                detections.clearSessions(
                        BuiltInDetector.Kind.FRUSTRATION,
                        projectId,
                        e.getKey().classifierId(),
                        e.getKey().callSiteId(),
                        cited.subList(from, Math.min(from + CLEAR_CHUNK, cited.size())),
                        now);
            }
            sessions += cited.size();
        }
        return sessions;
    }

    private record Scope(String classifierId, String callSiteId) {}
}
