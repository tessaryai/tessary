// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
 * The behaviour findings' triage store: the brief, the ruling, and the detector-state writes a ruling makes. The bugs
 * are a write on the wrong reference, a missing one that re-alarms on accepted traffic, and a failed advisory write
 * that throws away a paid ruling.
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
     * A negative tool-error ruling is recorded and folded back so the arm restarts from a reference that holds it.
     * The fold is advisory: if it fails, the ruling, case gate, and job completion still stand.
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
     * "Legitimate" re-learns the call site's reference from now; "real deviation" leaves it and opens the case.
     * Crossing them silences a regression or re-alarms on accepted traffic.
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

    /** An unclaimed cause kind is refused by name, never ruled without a state write. */
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
     * Absorbing a tool-error window installs its counts as the reference, clears any pending pin, and restarts the
     * accumulator; absorbing a drift finding re-pins its baseline.
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
        when(baselines.findById(PROJECT, "mbl-1")).thenReturn(Optional.of(baselineWithNoClosedDay()));

        source().repin(PROJECT, "f2", "u1");

        // No control day has closed, so the filling window becomes the reference.
        verify(baselines)
                .repin(
                        eq("mbl-1"),
                        eq("{\"sketch\":1}"),
                        isNull(),
                        isNull(),
                        isNull(),
                        anyString(),
                        isNull(),
                        anyString());
    }

    /**
     * An absorb refuses rather than guesses: a tool-error blob with no onset-run counts (it would install a lifetime
     * average), or a drift finding naming no baseline.
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
     * Absorbing folds every spell: two 300-call spells clear the 500-call floor together. Pinning only the newest
     * leaves the earlier one alarming.
     */
    @Test
    void absorbingACaseFoldsEverySpellItHolds() {
        source().repinCase(PROJECT, List.of(toolError("f2", 300, 30), toolError("f1", 300, 20)), "u1");

        verify(toolErrorReferences).pin(eq(PROJECT), eq(BUCKET), eq(600L), eq(50L), eq("u1"), anyString());
        verify(toolErrorStates).reset(eq(PROJECT), eq(BUCKET), eq("u1"), eq("Absorbed."), anyString());
    }

    /** One unreadable spell refuses the whole absorb. */
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

    /** A withheld classifier's finding is not found through every door: analysis and a person's ruling alike. */
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

    private static MetricBaselineRow baselineWithNoClosedDay() {
        return new MetricBaselineRow(
                "mbl-1",
                PROJECT,
                "sig-1",
                MetricBaselineRow.Measure.TURN_DURATION,
                MetricBaselineRow.BucketKind.CALL_SITE,
                "summarize",
                MetricBaselineRow.State.ARMED,
                null,
                null,
                null,
                "{\"sketch\":1}",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                "2026-09-01T00:00:00Z",
                "2026-09-01T00:00:00Z");
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

    /** Unreached collaborators are null, so a new dependency fails loudly. */
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
