// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorService;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * The behaviour findings' triage store: the brief it hands a run, the ruling it records, and the
 * detector-state writes a person's ruling makes. Each of these is a write the next sweep reads, so the bugs
 * are a write that lands on the wrong reference, a missing one that lets the detector re-alarm on traffic a
 * person accepted, and a failed advisory write that throws away a ruling a microVM paid for.
 */
@ExtendWith(MockitoExtension.class)
class BehaviorTriageSourceTest {

    private static final String PROJECT = "proj-1";
    private static final String BUCKET = "tool:search_docs";

    @Mock
    FindingRepository findings;

    @Mock
    ClassifierRepository signals;

    @Mock
    ClassifierService classifiers;

    @Mock
    BehaviorTriageJobRepository jobs;

    @Mock
    BehaviorTriageEngine engine;

    @Mock
    CaseOpener caseOpener;

    @Mock
    MetricBaselineRepository baselines;

    @Mock
    ToolErrorReferenceRepository toolErrorReferences;

    @Mock
    ToolErrorStateRepository toolErrorStates;

    @Mock
    ToolErrorService toolErrors;

    @Mock
    BehaviorBaselineEventRepository events;

    @Mock
    GroundednessRateRepository groundednessRates;

    @Mock
    ToolErrorStateRepository groundednessStates;

    @Mock
    Tracer tracer;

    /**
     * A negative ruling on a tool-error window is recorded, folded back into the detector so the arm it
     * fired on restarts from a reference that holds it, and the job is done. The fold is advisory: when it
     * fails the ruling still stands, the case gate still runs and the job is still done, rather than the
     * worker retrying a run that already produced its answer.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNegativeToolErrorRulingIsRecordedFoldedAndDone(boolean foldFails) {
        FindingRow finding = toolError("f1", 600, 60);
        BehaviorTriageJobRow job = job("f1");
        BehaviorTriageVerdict verdict = new BehaviorTriageVerdict(
                FindingRow.TriageVerdict.NEGATIVE,
                "ordinary traffic",
                List.of(new BehaviorTriageVerdict.Citation("n_cur", "600 calls", null)));
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(finding));
        when(engine.dossier(job, finding)).thenReturn(Map.of("finding.md", "the claim"));
        when(engine.buildPrompt(job, finding)).thenReturn("rule on it");
        when(engine.rule(PROJECT, "f1", Map.of("finding.md", "the claim"), "rule on it"))
                .thenReturn(verdict);
        when(engine.citationsJson(verdict)).thenReturn("[]");
        when(findings.recordTriage(
                        eq(PROJECT), eq("f1"), eq("negative"), eq("ordinary traffic"), eq("[]"), anyString()))
                .thenReturn(1);
        if (foldFails) {
            doThrow(new IllegalStateException("db blip"))
                    .when(toolErrors)
                    .foldRuledNegative(
                            eq(PROJECT), eq("search_docs"), eq("f1"), eq(finding.payloadJson()), anyString());
        }

        worker().triageForTest(job);

        verify(toolErrors)
                .foldRuledNegative(eq(PROJECT), eq("search_docs"), eq("f1"), eq(finding.payloadJson()), anyString());
        verify(caseOpener).ensureCaseFor(PROJECT, "f1", null);
        verify(jobs).markDone("job-f1");
    }

    /** A finding ruled or closed while its job waited has nothing to rule on, so no run is briefed. */
    @Test
    void aRuledFindingIsNotBriefed() {
        FindingRow ruled = FindingRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .id("f1")
                .triageVerdict(FindingRow.TriageVerdict.POSITIVE)
                .build();
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(ruled));

        assertEquals(Optional.empty(), source().brief(job("f1")));
        verifyNoInteractions(engine);
    }

    /**
     * "Legitimate" on a groundedness finding re-learns its call site's reference from now, so the absorbed
     * hours stop counting; "real deviation" leaves the reference alone and opens the case. Crossing them
     * either silences a regression or re-alarms on traffic a person accepted.
     */
    @ParameterizedTest
    @ValueSource(strings = {"expected", "not_expected"})
    void aGroundednessRulingRelearnsOnlyWhenAbsorbed(String action) {
        FindingRow finding = FindingRowBuilder.of(BuiltInDetector.Kind.GROUNDEDNESS)
                .id("f1")
                .projectId(PROJECT)
                .callSiteId("rag-answer")
                .payload("{\"cause_kind\":\"groundedness_rate\",\"native_cause_key\":\"rag-answer\"}")
                .build();
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(finding));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        boolean absorb = "expected".equals(action);
        if (absorb) when(groundednessRates.states()).thenReturn(groundednessStates);

        source().resolve(PROJECT, "f1", action, "u1");

        verify(findings)
                .recordHumanRuling(
                        eq(PROJECT),
                        eq("f1"),
                        eq(absorb ? "negative" : "positive"),
                        eq(absorb ? "A person ruled this legitimate." : "A person ruled this a real deviation."),
                        anyString());
        if (absorb) {
            verify(groundednessStates)
                    .resetAndRelearn(eq(PROJECT), eq("rag-answer"), eq("u1"), eq("Absorbed."), anyString());
            verify(caseOpener, never()).ensureCaseFor(any(), any(), any());
        } else {
            verifyNoInteractions(groundednessRates);
            verify(caseOpener).ensureCaseFor(PROJECT, "f1", "u1");
        }
    }

    /** A cause kind no branch claims is refused by name, never ruled with no detector-state write. */
    @Test
    void aCauseKindNoBranchClaimsIsRefused() {
        FindingRow finding = FindingRowBuilder.of(BuiltInDetector.Kind.REGEX)
                .id("f1")
                .projectId(PROJECT)
                .build();
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(finding));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());

        TessaryException e =
                assertThrows(TessaryException.class, () -> source().resolve(PROJECT, "f1", "expected", "u1"));

        assertEquals(ClassifierError.FINDING_NOT_FOUND, e.error());
        verify(findings, never()).recordHumanRuling(any(), any(), any(), any(), any());
    }

    /**
     * Absorbing a tool-error window with enough calls installs its counts as the tool's reference, clears
     * any pending pin and restarts the accumulator, so the tool stops alarming on the spell a person
     * accepted. Absorbing a metric-drift finding re-pins its baseline.
     */
    @Test
    void absorbingRepinsTheReferenceTheFindingWasRuledAgainst() {
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(toolError("f1", 600, 60)));

        source().repin(PROJECT, "f1", "u1");

        verify(toolErrorReferences).pin(eq(PROJECT), eq(BUCKET), eq(600L), eq(60L), eq("u1"), anyString());
        verify(toolErrorStates).clearPendingPin(eq(PROJECT), eq(BUCKET), anyString());
        verify(toolErrorStates).reset(eq(PROJECT), eq(BUCKET), eq("u1"), eq("Absorbed."), anyString());

        FindingRow shift = FindingRowBuilder.of(BuiltInDetector.Kind.DURATION_DRIFT)
                .id("f2")
                .projectId(PROJECT)
                .subject(FindingRow.SubjectKind.METRIC_BASELINE, "mbl-1")
                .payload("{\"cause_kind\":\"distribution_shift\"}")
                .build();
        when(findings.findById(PROJECT, "f2")).thenReturn(Optional.of(shift));
        when(baselines.findById(PROJECT, "mbl-1")).thenReturn(Optional.of(mock(MetricBaselineRow.class)));

        source().repin(PROJECT, "f2", "u1");

        verify(baselines)
                .repin(eq("mbl-1"), isNull(), isNull(), isNull(), isNull(), anyString(), isNull(), anyString());
    }

    /**
     * An absorb whose reference cannot be located refuses rather than guessing: a tool-error blob with no
     * onset-run counts (absorbing it would install a lifetime average as the new normal), and a drift
     * finding that names no metric baseline.
     */
    @Test
    void anAbsorbWithNoReferenceToMoveIsRefused() {
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of());
        FindingRow lifetime = FindingRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .id("f1")
                .projectId(PROJECT)
                .payload("{\"cause_kind\":\"rate_shift\",\"bucket\":{\"key\":\"" + BUCKET + "\"},\"n_cur\":900}")
                .build();
        FindingRow unscoped = FindingRowBuilder.of(BuiltInDetector.Kind.DURATION_DRIFT)
                .id("f2")
                .projectId(PROJECT)
                .payload("{\"cause_kind\":\"distribution_shift\"}")
                .build();
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(lifetime));
        when(findings.findById(PROJECT, "f2")).thenReturn(Optional.of(unscoped));

        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(TessaryException.class, () -> source().repin(PROJECT, "f1", "u1"))
                        .error());
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(TessaryException.class, () -> source().repin(PROJECT, "f2", "u1"))
                        .error());
        verifyNoInteractions(toolErrorReferences, baselines);
    }

    /**
     * Absorbing a tool-error case folds every spell it holds into one reference: two spells of 300 calls
     * each clear the 500-call floor together though neither does alone. Pinning only the newest would leave
     * the reference blind to the earlier spell, and the tool alarms again on it.
     */
    @Test
    void absorbingACaseFoldsEverySpellItHolds() {
        source().repinCase(PROJECT, List.of(toolError("f2", 300, 30), toolError("f1", 300, 20)), "u1");

        verify(toolErrorReferences).pin(eq(PROJECT), eq(BUCKET), eq(600L), eq(50L), eq("u1"), anyString());
        verify(toolErrorStates).reset(eq(PROJECT), eq(BUCKET), eq("u1"), eq("Absorbed."), anyString());
    }

    /** One spell the case cannot read refuses the whole absorb rather than under-counting what is accepted. */
    @Test
    void aCaseHoldingAnUnreadableSpellIsNotAbsorbed() {
        FindingRow unreadable = FindingRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .id("f1")
                .payload("{\"cause_kind\":\"rate_shift\"}")
                .build();

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> source().repinCase(PROJECT, List.of(toolError("f2", 300, 30), unreadable), "u1"));

        assertEquals(ClassifierError.FINDING_NOT_FOUND, e.error());
        verifyNoInteractions(toolErrorReferences, toolErrorStates);
    }

    /**
     * A finding whose classifier the org does not hold reads as not found through every door: analysis
     * (no run booted on it) and a person's ruling alike.
     */
    @Test
    void aFindingOfAWithheldClassifierIsNotReachable() {
        when(findings.findById(PROJECT, "f1")).thenReturn(Optional.of(toolError("f1", 600, 60)));
        when(classifiers.unavailableDetectorKinds(PROJECT)).thenReturn(Set.of(BuiltInDetector.Kind.TOOL_ERROR));

        assertEquals(Optional.empty(), source().analyze(PROJECT, "f1"));
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(TessaryException.class, () -> source().repin(PROJECT, "f1", "u1"))
                        .error());
        verifyNoInteractions(jobs);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private static FindingRow toolError(String id, long nCur, long failures) {
        return FindingRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .id(id)
                .projectId(PROJECT)
                .subject(FindingRow.SubjectKind.TOOL, "search_docs")
                .payload("{\"cause_kind\":\"rate_shift\",\"native_cause_key\":\"tool_error_rate:" + BUCKET + ":up\","
                        + "\"bucket\":{\"key\":\"" + BUCKET + "\"},\"rate\":{\"ref\":0.02,\"cur\":0.1},\"n_cur\":"
                        + nCur + ",\"failures\":{\"cur\":" + failures + "},\"counts_basis\":\"onset\"}")
                .build();
    }

    private static BehaviorTriageJobRow job(String findingId) {
        return new BehaviorTriageJobRow(
                "job-" + findingId,
                PROJECT,
                findingId,
                "claimed",
                "owner",
                "2026-09-16T00:00:00Z",
                1,
                null,
                "2026-09-15T00:00:00Z",
                "2026-09-16T00:00:00Z");
    }

    private BehaviorTriageWorker worker() {
        return new BehaviorTriageWorker(
                jobs,
                List.of(source()),
                engine,
                new ClassifierProperties(),
                new ObserverProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor(),
                new TriageLauncherBreaker(new ClassifierProperties()));
    }

    /** The collaborators no test here reaches are null, so a new dependency on one fails loudly. */
    @SuppressWarnings("NullAway")
    private BehaviorTriageSource source() {
        return new BehaviorTriageSource(
                findings,
                null,
                signals,
                classifiers,
                jobs,
                engine,
                caseOpener,
                baselines,
                toolErrorReferences,
                toolErrorStates,
                toolErrors,
                events,
                new ObjectMapper(),
                null,
                null,
                null,
                groundednessRates,
                null);
    }
}
