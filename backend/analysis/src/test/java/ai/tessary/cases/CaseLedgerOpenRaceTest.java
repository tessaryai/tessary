// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.finding.FindingRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The race the database settles and a single-threaded integration test cannot stage: two openers find no
 * live case for a key, and the second's insert is declined by the live-case index because the first's landed
 * in between. The loser must join the winner's case, never open a second one or drop the finding.
 */
@ExtendWith(MockitoExtension.class)
class CaseLedgerOpenRaceTest {

    private static final String PROJECT = "proj-1";
    private static final Instant NOW = Instant.parse("2026-07-02T10:00:00Z");

    @Mock
    CaseRepository cases;

    @Mock
    CaseEventRepository events;

    @Mock
    FindingRepository findings;

    private final CaseDetection detection = new CaseDetection(
            new CaseKey(CaseRow.Detector.CLASSIFIER, CaseRow.SubjectKind.CLASSIFIER, "subject-a", "rate"),
            "subject a",
            null,
            "find-2",
            "something happened",
            "because the detector said so",
            0.4,
            Instant.parse("2026-07-01T10:00:00Z"),
            null,
            null,
            null);

    @Test
    void anOpenerThatLostTheRaceJoinsTheWinnersCase() {
        CaseRow winner = winner();
        when(cases.findLive(PROJECT, detection.key())).thenReturn(Optional.empty(), Optional.of(winner));
        when(cases.open(PROJECT, detection, NOW)).thenReturn(Optional.empty());
        when(findings.attachToCase(PROJECT, "find-2", "case-1", NOW.toString())).thenReturn(true);
        when(cases.findById(PROJECT, "case-1")).thenReturn(Optional.of(winner));

        CaseRow joined = new CaseLedger(cases, events, findings).openOrJoin(PROJECT, detection, null, NOW);

        assertSame(winner, joined);
        verify(events).append(eq(PROJECT), eq("case-1"), eq(CaseEventRow.Kind.RECURRED), any(), any(), any(), eq(NOW));
        verify(events, never()).append(any(), any(), eq(CaseEventRow.Kind.OPENED), any(), any(), any(), any());
    }

    /** An insert declined with no live case to join is a state the index forbids: said loudly, not swallowed. */
    @Test
    void aDeclinedOpenWithNoLiveCaseFailsLoudly() {
        when(cases.findLive(PROJECT, detection.key())).thenReturn(Optional.empty());
        when(cases.open(PROJECT, detection, NOW)).thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> new CaseLedger(cases, events, findings).openOrJoin(PROJECT, detection, null, NOW));
    }

    private static CaseRow winner() {
        return new CaseRow(
                "case-1",
                1L,
                CaseRow.Detector.CLASSIFIER,
                CaseRow.SubjectKind.CLASSIFIER,
                "subject-a",
                "subject a",
                null,
                "rate",
                1L,
                "find-1",
                CaseRow.State.OPEN,
                null,
                "something happened",
                "because the detector said so",
                0.4,
                "2026-07-01T10:00:00Z",
                null,
                null,
                null,
                "2026-07-02T09:59:59Z",
                "2026-07-02T09:59:59Z",
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
