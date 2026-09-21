// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A {@code false_alarm} resolve clears the flag on every conversation cited by every frustration finding of the
 * case, scoped to the classifier that filed it, and nothing else: trace witnesses are not conversations and a
 * finding of another cause is not read.
 */
class FrustrationSessionClearerTest {

    private static final String PROJECT = "p1";
    private static final String NOW = "2026-08-05T00:00:00Z";

    private final FindingRepository findings = mock(FindingRepository.class);
    private final FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
    private final ClassifierDetectionWriteRepository detections = mock(ClassifierDetectionWriteRepository.class);
    private final FrustrationSessionClearer clearer = new FrustrationSessionClearer(findings, evidence, detections);

    @Test
    void clearsEverySessionOfEveryFindingInTheCase() {
        when(findings.listByCase(PROJECT, "case-1"))
                .thenReturn(List.of(finding("f1", "frustration_rate"), finding("f2", "frustration_rate")));
        when(evidence.listByFindings(PROJECT, List.of("f1", "f2")))
                .thenReturn(Map.of(
                        "f1", List.of(session("f1", "conv-a"), trace("f1", "tr-a"), session("f1", "conv-b")),
                        "f2", List.of(session("f2", "conv-b"), session("f2", "conv-c"), trace("f2", "tr-c"))));

        int cleared = clearer.clear(PROJECT, "case-1", NOW);

        assertEquals(3, cleared, "three distinct conversations, conv-b cited by both spells");
        verify(detections)
                .clearSessions(
                        BuiltInDetector.Kind.FRUSTRATION, PROJECT, "sig-1", List.of("conv-a", "conv-b", "conv-c"), NOW);
    }

    @Test
    void aCaseWithNoFrustrationFindingClearsNothing() {
        when(findings.listByCase(PROJECT, "case-2")).thenReturn(List.of(finding("f3", "rate_shift")));

        assertEquals(0, clearer.clear(PROJECT, "case-2", NOW));
        verify(evidence, never()).listByFindings(anyString(), any());
        verify(detections, never()).clearSessions(anyString(), anyString(), anyString(), any(), eq(NOW));
    }

    @Test
    void aFindingWithOnlyTraceWitnessesClearsNothing() {
        when(findings.listByCase(PROJECT, "case-3")).thenReturn(List.of(finding("f4", "frustration_rate")));
        when(evidence.listByFindings(PROJECT, List.of("f4"))).thenReturn(Map.of("f4", List.of(trace("f4", "tr-x"))));

        assertEquals(0, clearer.clear(PROJECT, "case-3", NOW));
        verify(detections, never()).clearSessions(anyString(), anyString(), anyString(), any(), eq(NOW));
    }

    private static FindingEvidenceRow session(String findingId, String sessionId) {
        return row(findingId, sessionId, null);
    }

    private static FindingEvidenceRow trace(String findingId, String traceId) {
        return row(findingId, null, traceId);
    }

    private static FindingEvidenceRow row(String findingId, @Nullable String sessionId, @Nullable String traceId) {
        return new FindingEvidenceRow(
                findingId + "-" + (sessionId == null ? traceId : sessionId),
                PROJECT,
                findingId,
                sessionId,
                traceId,
                null,
                FindingEvidenceRow.Role.WITNESS,
                null,
                NOW);
    }

    private static FindingRow finding(String id, String causeKind) {
        return new FindingRow(
                id,
                PROJECT,
                BuiltInDetector.Kind.FRUSTRATION,
                "cause-" + id,
                FindingRow.SubjectKind.CLASSIFIER,
                "sig-1",
                "Frustration",
                "cs-chat",
                FindingRow.Status.OPEN,
                "2026-08-01T00:00:00Z",
                "2026-08-01T00:00:00Z",
                null,
                null,
                null,
                10,
                "{\"cause_kind\":\"" + causeKind + "\"}",
                null,
                null,
                null,
                FindingRow.TriageVerdict.POSITIVE,
                FindingRow.TriageAction.OPENED_CASE,
                FrustrationEvidence.SUMMARY,
                null,
                "2026-08-02T00:00:00Z",
                null,
                "case-1",
                "2026-08-02T00:00:00Z",
                "2026-08-02T00:00:00Z");
    }
}
