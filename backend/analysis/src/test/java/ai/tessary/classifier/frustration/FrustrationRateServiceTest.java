// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.CauseKey;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * What a rising spell writes, with every repository mocked: one finding per spell, ruled positive when it is filed
 * and handed to the case opener; the same spell on a later pass refreshes that finding instead; and the evidence
 * is the frustrated conversations since onset as session and trace witness pairs.
 */
class FrustrationRateServiceTest {

    private static final String PROJECT = "p1";
    private static final String CALL_SITE = "cs-support-chat";
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final String CAUSE = CauseKey.frustration("sig-1", CALL_SITE);

    private final ObjectMapper mapper = new ObjectMapper();
    private final FrustrationRateRepository rates = mock(FrustrationRateRepository.class);
    private final ToolErrorStateRepository states = mock(ToolErrorStateRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
    private final CaseOpener caseOpener = mock(CaseOpener.class);
    private final FrustrationRateService service = new FrustrationRateService(
            rates, findings, evidence, caseOpener, TransactionOperations.withoutTransaction(), mapper);
    private final Instant at = START.plus(Duration.ofDays(3));

    @BeforeEach
    void wire() {
        List<HourlyToolTally> tallies = new ArrayList<>();
        for (int h = 0; h < 10; h++) tallies.add(tally(h, 1)); // 200 conversations at 5%
        for (int h = 10; h < 40; h++) tallies.add(tally(h, 5)); // then 25%
        when(rates.newestTurnAt(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(
                        Instant.parse(tallies.get(tallies.size() - 1).bucket())));
        when(rates.hourlyTallies(anyString(), anyString(), anyString(), any())).thenReturn(tallies);
        when(rates.states()).thenReturn(states);
        when(states.list(PROJECT)).thenReturn(List.of());
        when(findings.findOpenByCause(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(findings.recordTriage(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(1);
    }

    private static HourlyToolTally tally(int hour, long frustrated) {
        return new HourlyToolTally(START.plus(Duration.ofHours(hour)).toString(), CALL_SITE, 20, frustrated);
    }

    @Test
    void aNewOnsetFilesANewFindingBesideTheOldOne() {
        Spell spell = firstSpell();
        String earlier = Instant.parse(spell.decision().onsetAt())
                .minus(Duration.ofDays(1))
                .toString();
        when(findings.findOpenByCause(PROJECT, "frustration", CAUSE)).thenReturn(Optional.of(ruled("f0", earlier)));
        when(findings.recordRecomputedRate(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(new FindingRepository.Recorded("f2", true, 0, null));

        service.refresh(PROJECT, signal(), at);

        verify(findings)
                .recordTriage(
                        PROJECT,
                        "f2",
                        FindingRow.TriageVerdict.POSITIVE,
                        FrustrationEvidence.SUMMARY,
                        null,
                        at.toString());
        verify(caseOpener).ensureCaseFor(PROJECT, "f2", null);
        verify(findings, never())
                .refreshRuledObservation(eq(PROJECT), eq("f0"), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void aSpellARulingAlreadyCoversRefreshesTheOpenFindingRatherThanForkingOne() {
        Spell spell = firstSpell();
        String moved = Instant.parse(spell.decision().onsetAt())
                .minus(Duration.ofHours(1))
                .toString();
        when(findings.findOpenByCause(PROJECT, "frustration", CAUSE)).thenReturn(Optional.of(ruled("f1", moved)));

        service.refresh(PROJECT, signal(), at);

        verify(findings)
                .refreshRuledObservation(eq(PROJECT), eq("f1"), anyLong(), anyString(), anyString(), anyString());
        verify(findings, never()).recordTriage(anyString(), anyString(), anyString(), anyString(), any(), anyString());
        verify(caseOpener, never()).ensureCaseFor(anyString(), anyString(), any());
    }

    // ---- fixtures

    private Spell firstSpell() {
        FrustrationRateService probe = new FrustrationRateService(
                rates,
                mock(FindingRepository.class),
                mock(FindingEvidenceRepository.class),
                mock(CaseOpener.class),
                TransactionOperations.withoutTransaction(),
                mapper);
        List<Spell> spells = probe.refresh(PROJECT, signal(), at);
        assertEquals(1, spells.size());
        assertNotNull(spells.get(0).decision().onsetAt());
        return spells.get(0);
    }

    private static FindingRow ruled(String id, @Nullable String onsetAt) {
        return new FindingRow(
                id,
                PROJECT,
                BuiltInDetector.Kind.FRUSTRATION,
                CAUSE,
                FindingRow.SubjectKind.CLASSIFIER,
                "sig-1",
                "Frustration",
                CALL_SITE,
                FindingRow.Status.OPEN,
                onsetAt == null ? "" : onsetAt,
                onsetAt == null ? "" : onsetAt,
                null,
                null,
                null,
                10,
                "{\"cause_kind\":\"frustration_rate\"}",
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

    private static ClassifierRow signal() {
        return new ClassifierRow(
                "sig-1",
                PROJECT,
                "frustration",
                "Frustration",
                null,
                BuiltInDetector.Kind.FRUSTRATION,
                null,
                true,
                9,
                true,
                ClassifierRow.Mode.TRACKING,
                "2026-08-01T00:00:00Z",
                "2026-08-01T00:00:00Z");
    }
}
