// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDtos.ClassifierHealthView;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricDriftDetector;
import ai.tessary.classifier.metric.MetricHistogram;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.classifier.worker.ClassifierSweep;
import ai.tessary.classifier.worker.ClassifierSweepRegistry;
import ai.tessary.classifier.worker.SweepRouting;
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
import ai.tessary.testsupport.Props;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

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
    private static final String UNDECLARED = "undeclared_kind";

    private final ClassifierRepository signals = mock(ClassifierRepository.class);
    private final ClassifierJobRepository jobs = mock(ClassifierJobRepository.class);
    private final MetricBaselineRepository baselines = mock(MetricBaselineRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final DecisionProviderResolver decisionProviders = mock(DecisionProviderResolver.class);
    private final ClassifierProperties props = Props.of(ClassifierProperties.class);
    private final BuiltInClassifierCatalog catalog = mock(BuiltInClassifierCatalog.class);

    /** A sweep from outside the tree, for a kind no catalog module declares. */
    private final ClassifierSweep undeclaredSweep = mock(ClassifierSweep.class);

    private final ClassifierService service = new ClassifierService(
            signals,
            mock(ClassifierDetectionRepository.class),
            jobs,
            catalog,
            new SweepRouting(catalog, sweepsFor(undeclaredSweep, UNDECLARED)),
            mock(SubstrateReadRepository.class),
            MAPPER,
            props,
            mock(CapabilityService.class),
            projects,
            baselines,
            decisionProviders,
            mock(EncoderAvailability.class),
            new GroundednessProperties());

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
    @Test
    void aWriteThatMatchesNoRowIsNotFound() {
        when(signals.findById(PID, "sig-a")).thenReturn(Optional.of(row()));
        when(signals.setEnabled(PID, "sig-a", false)).thenReturn(0);

        TessaryException e = assertThrows(TessaryException.class, () -> service.setEnabled(PID, "sig-a", false));

        assertEquals(ClassifierError.NOT_FOUND, e.error());
    }

    /**
     * Catches an enable written before the sweep that runs the row is asked: the sweep's refusal reaches the caller
     * as it was thrown, and the row stays off and paused as it was.
     */
    @Test
    void aSweepThatRefusesTheRowKeepsItOff() {
        ClassifierRow row = undeclaredRow();
        when(signals.findById(PID, "cls-u")).thenReturn(Optional.of(row));
        TessaryException refusal = new TessaryException(ClassifierError.NOT_FOUND, "x");
        doThrow(refusal).when(undeclaredSweep).checkEnable(row);

        List<Map<String, Object>> events = logged(
                ClassifierService.class,
                "classifier.enable.refused",
                () -> assertSame(
                        refusal, assertThrows(TessaryException.class, () -> service.setEnabled(PID, "cls-u", true))));

        assertEquals(
                List.of(Map.of(
                        "event",
                        "classifier.enable.refused",
                        "project",
                        PID,
                        "classifier",
                        "cls-u",
                        "detector",
                        UNDECLARED,
                        "error_code",
                        ClassifierError.NOT_FOUND.code())),
                events,
                "a sweep's refusal reaches only the caller, with no line that names the classifier and the code");
        verify(signals, never()).setEnabled(anyString(), anyString(), anyBoolean());
        verify(signals, never()).unpause(anyString(), anyString());
    }

    /** Catches a disable that a refusing sweep blocks: turning a row off never asks the sweep. */
    @Test
    void turningARowOffNeverAsksTheSweep() {
        ClassifierRow row = undeclaredRow();
        when(signals.findById(PID, "cls-u")).thenReturn(Optional.of(row));
        doThrow(new TessaryException(ClassifierError.NOT_FOUND, "x"))
                .when(undeclaredSweep)
                .checkEnable(any());
        when(signals.setEnabled(PID, "cls-u", false)).thenReturn(1);

        assertSame(row, service.setEnabled(PID, "cls-u", false));

        verify(undeclaredSweep, never()).checkEnable(any());
        verify(signals).setEnabled(PID, "cls-u", false);
    }

    /** Catches the check replacing the write: a sweep that accepts the row lets the enable and the unpause land. */
    @Test
    void aSweepThatAcceptsTheRowLetsItOn() {
        ClassifierRow row = undeclaredRow();
        when(signals.findById(PID, "cls-u")).thenReturn(Optional.of(row));
        when(signals.setEnabled(PID, "cls-u", true)).thenReturn(1);

        service.setEnabled(PID, "cls-u", true);

        verify(undeclaredSweep).checkEnable(row);
        verify(signals).setEnabled(PID, "cls-u", true);
        verify(signals).unpause(PID, "cls-u");
    }

    private static ClassifierRow undeclaredRow() {
        return ClassifierRowBuilder.of(UNDECLARED)
                .id("cls-u")
                .custom()
                .disabled()
                .discovery()
                .at(T0)
                .build();
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

    /**
     * Widening the call sites of a classifier a registered sweep runs does not rewind it. Its kind runs at window
     * grain, so a rewind would send every match in the history to the model again and count it twice in fitted state;
     * read from the catalog alone, the kind takes the observation default and is rewound.
     */
    @Test
    void wideningAKindASweepRunsDoesNotRewindIt() {
        when(catalog.grainFor(UNDECLARED)).thenReturn(ClassifierModelModule.Grain.OBSERVATION);
        ClassifierRow before = ClassifierRowBuilder.of(UNDECLARED)
                .id("clf-u")
                .custom()
                .onCallSites("cs-1")
                .build();
        when(signals.findById(PID, "clf-u")).thenReturn(Optional.of(before));
        when(signals.setCallSiteIds(PID, "clf-u", null)).thenReturn(1);

        service.setCallSiteIds(PID, "clf-u", null);

        verify(jobs, never()).rewindCursor(any(), any());
    }

    /** An undeclared kind no sweep claims still scores spans with no model, so widening it rewinds as before. */
    @Test
    void wideningAnUndeclaredKindNoSweepRunsRewindsIt() {
        when(catalog.grainFor("other_undeclared_kind")).thenReturn(ClassifierModelModule.Grain.OBSERVATION);
        ClassifierRow before = ClassifierRowBuilder.of("other_undeclared_kind")
                .id("clf-o")
                .custom()
                .onCallSites("cs-1")
                .build();
        when(signals.findById(PID, "clf-o")).thenReturn(Optional.of(before));
        when(signals.setCallSiteIds(PID, "clf-o", null)).thenReturn(1);
        when(jobs.rewindCursor(PID, "clf-o")).thenReturn(1);

        service.setCallSiteIds(PID, "clf-o", null);

        verify(jobs).rewindCursor(PID, "clf-o");
    }

    private static ClassifierSweepRegistry sweepsFor(ClassifierSweep sweep, String kind) {
        when(sweep.kinds()).thenReturn(java.util.Set.of(kind));
        return new ClassifierSweepRegistry(TestObjectProvider.of(sweep));
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

    /** The structured fields of every event named {@code name} that {@code source}'s logger wrote while {@code action} ran. */
    private static List<Map<String, Object>> logged(Class<?> source, String name, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (ILoggingEvent e : appender.list) {
            if (e.getKeyValuePairs() == null) continue;
            Map<String, Object> fields = new HashMap<>();
            e.getKeyValuePairs().forEach(kv -> fields.put(kv.key, kv.value));
            if (name.equals(fields.get("event"))) events.add(fields);
        }
        return events;
    }
}
