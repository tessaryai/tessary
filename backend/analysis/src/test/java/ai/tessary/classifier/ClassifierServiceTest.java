// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDtos.ClassifierHealthView;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricDriftDetector;
import ai.tessary.classifier.metric.MetricHistogram;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.GroundednessProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.PlatformCreditExhausted;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.model.JobStatus;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.EncoderAvailability;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The metric-drift dial behind {@code GET/PUT .../tuning} and its false-alarm rate, {@link
 * MetricDriftDetector#impliedFalseAlarmRate} at the median readable bucket spread. Pins which spread, not the noise
 * law.
 *
 * <p>{@link ClassifierService#health} must surface a failing sweep (status, attempts, last error) without touching
 * the DB, and must report a healthy row for a signal that has never been enqueued. All collaborators are mocked: this
 * is the read-model logic, not the sweep itself (covered by {@link ClassifierJobRepositoryTest}).
 *
 * <p>Saving a key re-resolves the lane of every paused Frustration in the org. An org out of platform credit makes
 * that resolve throw; the key save must still succeed and leave the classifier paused.
 *
 * <p>The project-scoped writes behind the enable toggle and the mode switch. Both read the row through the tenant
 * guard first, then write under the same project; a write that matches no row (the row went between the two) must
 * answer NOT_FOUND rather than report a change that never landed.
 */
class ClassifierServiceTest {

    private static final String ORG = "org-1";
    private static final String PID = "proj-1";
    private static final String T0 = "2026-01-01T00:00:00Z";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClassifierRepository signals = mock(ClassifierRepository.class);
    private final ClassifierJobRepository jobs = mock(ClassifierJobRepository.class);
    private final MetricBaselineRepository baselines = mock(MetricBaselineRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final DecisionProviderResolver decisionProviders = mock(DecisionProviderResolver.class);
    private final ClassifierProperties props = new ClassifierProperties();
    private final ClassifierService service = new ClassifierService(
            signals,
            mock(ClassifierDetectionRepository.class),
            jobs,
            mock(BuiltInClassifierCatalog.class),
            mock(SubstrateReadRepository.class),
            MAPPER,
            props,
            mock(CapabilityService.class),
            projects,
            baselines,
            decisionProviders,
            mock(EncoderAvailability.class),
            new GroundednessProperties());

    @BeforeEach
    void setup() {
        props.setMaxAttempts(5);
    }

    /**
     * Catches the rate read off the mean spread, the first bucket, or nothing once one sketch is unreadable, and an
     * unpinned bucket skipped when its newest closed day has a sketch.
     */
    @Test
    void theImpliedRateReadsTheMedianReadableSpread() {
        // Spreads of about 0.75, 1.0, and 1.3, each implying a different rate.
        MetricHistogram tight = sketch(4.0, 5.5);
        MetricHistogram middle = sketch(3.5, 5.5);
        MetricHistogram wide = sketch(3.2, 5.8);
        when(signals.findById(PID, "clf-cost"))
                .thenReturn(Optional.of(row("clf-cost", BuiltInDetector.Kind.COST_DRIFT, null)));
        when(baselines.listByClassifier(PID, "clf-cost"))
                .thenReturn(List.of(
                        baseline("b-wide", wide.toJson(), null),
                        baseline("b-garbage", "{\"kind\":\"histogram\",\"bins\":", null),
                        baseline("b-empty", null, null),
                        baseline("b-flat", sketch(4.6).toJson(), null),
                        // Not pinned: its newest closed day is read.
                        baseline("b-middle", null, control(middle.toJson())),
                        baseline("b-tight", tight.toJson(), null)));

        ClassifierDtos.TuningView view = service.getTuning(PID, "clf-cost");

        MetricDriftConfig defaults = MetricDriftConfig.defaults();
        assertTrue(0 < tight.stdDevLog()
                && tight.stdDevLog() < middle.stdDevLog()
                && middle.stdDevLog() < wide.stdDevLog());
        assertEquals(
                3,
                java.util.stream.Stream.of(tight, middle, wide)
                        .map(h -> MetricDriftDetector.impliedFalseAlarmRate(
                                        defaults.w1Floor(), h.stdDevLog(), defaults.windowTargetCount())
                                .getAsDouble())
                        .distinct()
                        .count(),
                "each spread implies its own rate, or this test could not tell them apart");
        assertEquals(
                new ClassifierDtos.TuningView(
                        defaults.windowTargetCount(),
                        defaults.windowMaxHours(),
                        defaults.minSample(),
                        defaults.w1Floor(),
                        MetricDriftDetector.impliedFalseAlarmRate(
                                        defaults.w1Floor(), middle.stdDevLog(), defaults.windowTargetCount())
                                .getAsDouble()),
                view);
    }

    /** No supporting traffic reads as unknown, and a classifier with no window has no dial. */
    @Test
    void withNoReadableSpreadTheRateIsUnknownAndOtherDetectorsHaveNoDial() {
        when(signals.findById(PID, "clf-dur"))
                .thenReturn(Optional.of(row("clf-dur", BuiltInDetector.Kind.DURATION_DRIFT, null)));
        when(baselines.listByClassifier(PID, "clf-dur")).thenReturn(List.of(baseline("b-empty", null, null)));
        when(signals.findById(PID, "clf-leak"))
                .thenReturn(Optional.of(row("clf-leak", BuiltInDetector.Kind.SECRET_LEAK, null)));

        assertNull(service.getTuning(PID, "clf-dur").impliedFalseAlarmRate());
        TessaryException e = assertThrows(TessaryException.class, () -> service.getTuning(PID, "clf-leak"));
        assertEquals(ClassifierError.NOT_METRIC_DRIFT, e.error());
    }

    /** A write keeps unedited fields and the watched measures, and the response is the clamped value in effect. */
    @Test
    void aTuningWriteMergesIntoTheStoredConfigAndReportsTheClampedValue() throws Exception {
        when(signals.findById(PID, "clf-cost"))
                .thenReturn(Optional.of(row(
                        "clf-cost",
                        BuiltInDetector.Kind.COST_DRIFT,
                        "{\"measures\":[\"cost\"],\"settle_seconds\":120}")));

        ClassifierDtos.TuningView view = service.setTuning(PID, "clf-cost", 2_000, 48, 5, 0.2);

        assertEquals(new ClassifierDtos.TuningView(2_000, 48, 30, 0.2, null), view, "min_sample is clamped to 30");
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(signals).updateConfig(eq(PID), eq("clf-cost"), written.capture());
        JsonNode stored = MAPPER.readTree(written.getValue());
        assertEquals("[\"cost\"]", stored.path("measures").toString());
        assertEquals(120, stored.path("settle_seconds").asInt());
        assertEquals(2_000, stored.path("window_target_count").asInt());
        assertEquals(30, stored.path("min_sample").asInt());
    }

    @Test
    void neverEnqueuedSignal_reportsPendingWithNoFailureHistory() {
        when(signals.listByProject(PID)).thenReturn(List.of(signal("sig-a")));
        when(jobs.listByProject(PID)).thenReturn(List.of());

        List<ClassifierHealthView> health = service.health(PID);

        assertEquals(1, health.size());
        ClassifierHealthView v = health.get(0);
        assertEquals("sig-a", v.classifierId());
        assertEquals(ClassifierJobRow.PENDING, v.status());
        assertEquals(0, v.attempts());
        assertEquals(5, v.maxAttempts());
        assertNull(v.lastError());
        assertNull(v.lastSweptAt());
        assertNull(v.nextAttemptAt());
    }

    @Test
    void failingSweep_surfacesStatusAttemptsAndLastError() {
        when(signals.listByProject(PID)).thenReturn(List.of(signal("sig-b")));
        ClassifierJobRow failing = new ClassifierJobRow(
                "job-1",
                PID,
                "sig-b",
                JobStatus.FAILED,
                null,
                null,
                null,
                null,
                3,
                "classify: connection refused",
                "2026-01-01T00:00:00Z",
                "2026-01-05T12:00:00Z",
                0);
        when(jobs.listByProject(PID)).thenReturn(List.of(failing));

        ClassifierHealthView v = service.health(PID).get(0);

        assertEquals(JobStatus.FAILED, v.status());
        assertEquals(3, v.attempts());
        assertEquals("classify: connection refused", v.lastError());
        assertEquals("2026-01-05T12:00:00Z", v.lastSweptAt());
        assertNull(v.nextAttemptAt(), "no backoff stamp on main yet — null reads as 'next heartbeat'");
    }

    @Test
    void healthySweep_reportsDoneWithNoAlarmingState() {
        when(signals.listByProject(PID)).thenReturn(List.of(signal("sig-c")));
        ClassifierJobRow done = new ClassifierJobRow(
                "job-2",
                PID,
                "sig-c",
                JobStatus.DONE,
                "2026-01-05T00:00:00Z",
                "obs-9",
                null,
                null,
                0,
                null,
                "2026-01-01T00:00:00Z",
                "2026-01-05T00:00:00Z",
                0);
        when(jobs.listByProject(PID)).thenReturn(List.of(done));

        ClassifierHealthView v = service.health(PID).get(0);

        assertEquals(JobStatus.DONE, v.status());
        assertEquals(0, v.attempts());
        assertNull(v.lastError());
    }

    @Test
    void anOrgOutOfPlatformCreditKeepsItsPauseWhenAnotherKeyIsSaved() {
        when(projects.findByOrg(ORG))
                .thenReturn(List.of(new Project(PID, ORG, "p", "P", null, "t0", null, null, true, null)));
        when(signals.listByProject(PID)).thenReturn(List.of(frustration()));
        when(signals.findPause(PID, "cls-f"))
                .thenReturn(Optional.of(new ClassifierPause(ClassifierPause.NO_CREDIT, Instant.EPOCH)));
        when(decisionProviders.resolve(PID, ModelLane.FRUSTRATION)).thenThrow(new NoCredit());

        assertEquals(0, service.unpauseForProvider(ORG, ModelProvider.OPENAI));
        verify(signals, never()).unpause(PID, "cls-f");
    }

    /** Enabling asks whether the lane has a provider, not whether it has credit: a sweep pauses it as no_credit. */
    @Test
    void enablingFrustrationOnAProviderWithNoCreditLeftIsAccepted() {
        when(projects.findById(PID))
                .thenReturn(Optional.of(new Project(PID, ORG, "p", "P", null, "t0", null, null, true, null)));
        when(signals.findById(PID, "cls-f")).thenReturn(Optional.of(frustration()));
        when(signals.setEnabled(PID, "cls-f", true)).thenReturn(1);
        when(decisionProviders.hasProvider(PID, ModelLane.FRUSTRATION)).thenReturn(true);
        when(decisionProviders.resolve(PID, ModelLane.FRUSTRATION)).thenThrow(new NoCredit());

        service.setEnabled(PID, "cls-f", true);

        verify(signals).setEnabled(PID, "cls-f", true);
    }

    /** Catches a zero-row write being reported as a success, handing back a row the write never touched. */
    @ParameterizedTest
    @ValueSource(strings = {"enabled", "mode"})
    void aWriteThatMatchesNoRowIsNotFound(String write) {
        when(signals.findById(PID, "sig-a")).thenReturn(Optional.of(row()));
        Executable call;
        if ("enabled".equals(write)) {
            when(signals.setEnabled(PID, "sig-a", false)).thenReturn(0);
            call = () -> service.setEnabled(PID, "sig-a", false);
        } else {
            when(signals.setMode(PID, "sig-a", ClassifierRow.Mode.TRACKING)).thenReturn(0);
            call = () -> service.setMode(PID, "sig-a", ClassifierRow.Mode.TRACKING);
        }

        TessaryException e = assertThrows(TessaryException.class, call);

        assertEquals(ClassifierError.NOT_FOUND, e.error());
    }

    /** Catches an unknown operating point being written (and read back) as if it were a mode. */
    @Test
    void anUnknownModeIsRefusedBeforeAnythingIsReadOrWritten() {
        TessaryException e = assertThrows(TessaryException.class, () -> service.setMode(PID, "sig-a", "aggressive"));

        assertEquals(ClassifierError.INVALID_MODE, e.error());
        verifyNoInteractions(signals);
    }

    private static MetricHistogram sketch(double... logValues) {
        MetricHistogram h = new MetricHistogram(MetricHistogram.Grid.duration());
        for (double v : logValues) h.add(v);
        return h;
    }

    /** A control ring holding one closed day whose sketch is {@code sketchJson}. */
    private static String control(String sketchJson) {
        return "{\"kind\":\"control\",\"days\":[{\"d\":\"2026-09-01\",\"m\":" + sketchJson + "}]}";
    }

    private static ClassifierRow row(String id, String detector, @Nullable String configJson) {
        return ClassifierRowBuilder.of(detector)
                .id(id)
                .config(configJson)
                .custom()
                .discovery()
                .at(T0)
                .build();
    }

    private static ClassifierRow row() {
        return ClassifierRowBuilder.of("regex")
                .id("sig-a")
                .named("sig-sig-a", "Signal a")
                .custom()
                .discovery()
                .at(T0)
                .build();
    }

    private static ClassifierRow signal(String id) {
        return ClassifierRowBuilder.of("regex")
                .id(id)
                .named("sig-" + id, "Signal " + id)
                .custom()
                .discovery()
                .at(T0)
                .build();
    }

    private static ClassifierRow frustration() {
        return ClassifierRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION)
                .id("cls-f")
                .named("frustration", "Frustration")
                .discovery()
                .at(T0)
                .build();
    }

    private static MetricBaselineRow baseline(String id, @Nullable String pinnedSketch, @Nullable String controlJson) {
        return new MetricBaselineRow(
                id,
                PID,
                "clf",
                MetricBaselineRow.Measure.COST,
                "call_site",
                "cs-" + id,
                "active",
                pinnedSketch,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                controlJson,
                null,
                0,
                null,
                null,
                null,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
    }

    private static final class NoCredit extends TessaryException implements PlatformCreditExhausted {
        NoCredit() {
            super(ModelConfigError.MISSING_CREDENTIALS, ModelProvider.PLATFORM);
        }
    }
}
