// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.gate.PreDeployCheckService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * The observation sweep's page loop: a kind in the drain set keeps taking pages inside one claim until
 * the stream runs out, recording each page before reading the next; every other kind takes one page a
 * claim, as it always has; and a drain that loses its lease stops rather than moving a cursor it no
 * longer owns.
 */
@ExtendWith(MockitoExtension.class)
class ClassifierWorkerDrainTest {

    private static final String PROJECT = "proj-1";
    private static final String CLASSIFIER = "sig-1";
    private static final int PAGE = 2;

    @Mock
    ClassifierService signalService;

    @Mock
    ClassifierRepository signals;

    @Mock
    ClassifierJobRepository jobs;

    @Mock
    ClassifierDetectionWriteRepository detections;

    @Mock
    SubstrateReadRepository substrate;

    @Mock
    BuiltInClassifierCatalog catalog;

    @Mock
    PreDeployCheckService preDeployChecks;

    @Mock
    ClassifierArming arming;

    @Mock
    BuiltInDetector detector;

    @Mock
    ClassifierCatchUp catchUp;

    @Mock
    Tracer tracer;

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void attachAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(ClassifierWorker.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    void aDrainKindKeepsPagingInsideOneClaimUntilAPageComesBackShort() {
        ClassifierWorker worker = worker();
        armSignal(BuiltInDetector.Kind.SECRET_LEAK);
        List<SubstrateObservation> first = page(0, PAGE);
        List<SubstrateObservation> second = page(2, PAGE);
        List<SubstrateObservation> tail = page(4, 1);
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(first);
        when(substrate.observationsAfter(PROJECT, createdAt(first), handle(first), PAGE))
                .thenReturn(second);
        when(substrate.observationsAfter(PROJECT, createdAt(second), handle(second), PAGE))
                .thenReturn(tail);
        when(jobs.advanceCursor(eq("job-1"), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(true);

        worker.sweepForTest(job());

        verify(jobs).advanceCursor(eq("job-1"), anyString(), eq(createdAt(first)), eq(handle(first)), anyLong());
        verify(jobs).advanceCursor(eq("job-1"), anyString(), eq(createdAt(second)), eq(handle(second)), anyLong());
        verify(jobs, times(1)).markSwept("job-1", createdAt(tail), handle(tail));
        verify(arming, times(3)).evaluate(any(), eq(PROJECT), any(), any());
        verify(catchUp, times(1)).caughtUp(any(), any(), eq(createdAt(tail)));
    }

    @Test
    void aSweepThatStopsShortOfTheHeadDoesNotCatchUp() {
        ClassifierWorker worker = worker();
        armSignal(BuiltInDetector.Kind.REGEX);
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(page(0, PAGE));

        worker.sweepForTest(job());

        verify(catchUp, never()).caughtUp(any(), any(), any());
    }

    @Test
    void aKindOutsideTheDrainSetTakesOnePagePerClaim() {
        ClassifierWorker worker = worker();
        armSignal(BuiltInDetector.Kind.REGEX);
        List<SubstrateObservation> first = page(0, PAGE);
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(first);

        worker.sweepForTest(job());

        verify(jobs).markSwept("job-1", createdAt(first), handle(first));
        verify(jobs, never()).advanceCursor(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(substrate, times(1)).observationsAfter(anyString(), any(), any(), eq(PAGE));
    }

    @Test
    void aDrainThatLosesItsLeaseStopsWithoutReleasingTheJob() {
        ClassifierWorker worker = worker();
        armSignal(BuiltInDetector.Kind.MALFORMED_OUTPUT);
        List<SubstrateObservation> first = page(0, PAGE);
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(first);
        when(jobs.advanceCursor(eq("job-1"), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(false);

        worker.sweepForTest(job());

        verify(substrate, times(1)).observationsAfter(anyString(), any(), any(), eq(PAGE));
        verify(jobs, never()).markSwept(anyString(), any(), any());
        verify(catchUp, never()).caughtUp(any(), any(), any());
        assertTrue(
                appender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("lost its lease")),
                "a lost lease is said out loud: " + appender.list);
    }

    /**
     * The tick is the only thing that moves every project's sweeps, so no one step's failure may stop the
     * rest: a dead-letter sweep that fails (or that dead-lettered some jobs) still enqueues and claims, and
     * one project whose enqueue throws still leaves the next project enqueued.
     */
    @Test
    void aTickSurvivesAFailedDeadLetterSweepAndOneProjectsFailedEnqueue() {
        ClassifierWorker worker = worker();
        when(jobs.failExhausted(anyInt())).thenReturn(3).thenThrow(new IllegalStateException("db blip"));
        when(substrate.projectsWithObservations()).thenReturn(List.of("proj-a", "proj-b"));
        org.mockito.Mockito.doThrow(new IllegalStateException("bad config"))
                .when(signalService)
                .enqueueEnabled("proj-a");
        when(jobs.claimBatch(anyString(), anyInt(), anyLong(), anyInt())).thenReturn(List.of());

        worker.tick();
        worker.tick();

        verify(signalService, times(2)).enqueueEnabled("proj-b");
        verify(jobs, times(2)).claimBatch(anyString(), anyInt(), anyLong(), anyInt());
    }

    /** A project scan or a claim that fails ends the tick: no sweep runs off a list it could not read. */
    @Test
    void aFailedProjectScanOrClaimRunsNoSweep() {
        when(substrate.projectsWithObservations()).thenThrow(new IllegalStateException("db down"));
        worker().tick();
        verify(jobs, never()).claimBatch(anyString(), anyInt(), anyLong(), anyInt());

        org.mockito.Mockito.reset(substrate);
        when(substrate.projectsWithObservations()).thenReturn(List.of());
        when(jobs.claimBatch(anyString(), anyInt(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));
        worker().tick();

        verifyNoInteractions(signals);
    }

    /**
     * A job whose classifier was deleted or switched off finishes without moving its cursor. Leaving it
     * claimed re-runs it every tick; moving the cursor would skip traffic if the classifier comes back.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aJobForAMissingOrDisabledClassifierFinishesWithoutMovingItsCursor(boolean exists) {
        when(signals.findById(PROJECT, CLASSIFIER))
                .thenReturn(
                        exists
                                ? Optional.of(new ClassifierRow(
                                        CLASSIFIER,
                                        PROJECT,
                                        BuiltInDetector.Kind.REGEX,
                                        BuiltInDetector.Kind.REGEX,
                                        null,
                                        BuiltInDetector.Kind.REGEX,
                                        null,
                                        true,
                                        1,
                                        false,
                                        ClassifierRow.Mode.DISCOVERY,
                                        "now",
                                        "now"))
                                : Optional.empty());

        worker().sweepForTest(job());

        verify(jobs).markSwept("job-1", null, null);
        verifyNoInteractions(substrate);
    }

    /**
     * Everything after the detections are scored is fail-soft: a detection row that will not write, an
     * arming gate that throws, a pre-deploy registration that throws and a catch-up that throws each cost
     * only their own work. None may stop the cursor, or the sweep re-scores the same page forever. The other
     * fired detection still writes.
     */
    @Test
    void failuresAfterScoringNeverStopTheCursor() {
        ClassifierWorker worker = worker(PAGE + 1);
        String kind = BuiltInDetector.Kind.REGEX;
        when(signals.findById(PROJECT, CLASSIFIER)).thenReturn(Optional.of(enabled(kind)));
        when(catalog.grainFor(kind)).thenReturn(Grain.OBSERVATION);
        when(detections.writesDetections(kind)).thenReturn(true);
        when(catalog.detectorFor(kind)).thenReturn(detector);
        when(catchUp.kinds()).thenReturn(java.util.Set.of(kind));
        List<SubstrateObservation> tail = page(0, 2);
        when(substrate.observationsAfter(PROJECT, null, null, PAGE + 1)).thenReturn(tail);
        when(detector.sweepBatch(any(), any(), any()))
                .thenReturn(List.of(Detection.fired("high", "{}"), Detection.fired("low", "{}")));
        when(detections.insert(
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("trace-0"),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenThrow(new IllegalStateException("constraint"));
        when(detections.insert(
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("trace-1"),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("arming"))
                .when(arming)
                .evaluate(any(), eq(PROJECT), any(), any());
        when(preDeployChecks.isEnabled()).thenReturn(true);
        when(preDeployChecks.registerForSignal(any())).thenThrow(new IllegalStateException("ci"));
        org.mockito.Mockito.doThrow(new IllegalStateException("rollup"))
                .when(catchUp)
                .caughtUp(any(), any(), any());

        worker.sweepForTest(job());

        verify(jobs).markSwept("job-1", createdAt(tail), handle(tail));
        verify(catchUp).caughtUp(any(), any(), eq(createdAt(tail)));
        verify(jobs, never()).markFailed(anyString(), any(), anyInt());
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private ClassifierWorker worker() {
        return worker(PAGE);
    }

    private ClassifierWorker worker(int pageSize) {
        ClassifierProperties props = new ClassifierProperties();
        props.setBatchSize(pageSize);
        return new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                new ClassifierSweepRegistry(TestObjectProvider.of()),
                TestObjectProvider.of(catchUp),
                props,
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());
    }

    private static ClassifierRow enabled(String kind) {
        return new ClassifierRow(
                CLASSIFIER,
                PROJECT,
                kind,
                kind,
                null,
                kind,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                "now",
                "now");
    }

    /** An enabled classifier of {@code kind} at observation grain, whose detector fires on nothing. */
    private void armSignal(String kind) {
        when(signals.findById(PROJECT, CLASSIFIER))
                .thenReturn(Optional.of(new ClassifierRow(
                        CLASSIFIER,
                        PROJECT,
                        kind,
                        kind,
                        null,
                        kind,
                        null,
                        true,
                        1,
                        true,
                        ClassifierRow.Mode.DISCOVERY,
                        "now",
                        "now")));
        when(catalog.grainFor(kind)).thenReturn(Grain.OBSERVATION);
        when(detections.writesDetections(kind)).thenReturn(true);
        when(catalog.detectorFor(kind)).thenReturn(detector);
        org.mockito.Mockito.lenient().when(catchUp.kinds()).thenReturn(java.util.Set.of(kind));
        when(detector.sweepBatch(any(), any(), any())).thenAnswer(inv -> {
            List<?> batch = inv.getArgument(1);
            List<Detection> none = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) none.add(Detection.none());
            return none;
        });
    }

    private static ClassifierJobRow job() {
        return new ClassifierJobRow(
                "job-1",
                PROJECT,
                CLASSIFIER,
                ClassifierJobRow.PENDING,
                null,
                null,
                null,
                null,
                0,
                null,
                "now",
                "now",
                0);
    }

    /** {@code size} observations numbered from {@code from}, in cursor order. */
    private static List<SubstrateObservation> page(int from, int size) {
        List<SubstrateObservation> out = new ArrayList<>();
        for (int i = from; i < from + size; i++) {
            out.add(new SubstrateObservation(
                    "span-" + i,
                    PROJECT,
                    "trace-" + i,
                    null,
                    null,
                    null,
                    "llm",
                    "chat",
                    null,
                    "ok",
                    null,
                    "2026-09-13T00:00:0" + i + "Z",
                    null));
        }
        return out;
    }

    private static String createdAt(List<SubstrateObservation> page) {
        return page.get(page.size() - 1).createdAt();
    }

    private static String handle(List<SubstrateObservation> page) {
        SubstrateObservation last = page.get(page.size() - 1);
        return SubstrateReadRepository.handle(last.traceId(), last.observationId());
    }

    /**
     * The bug: on a machine whose own host name does not resolve, building the lease owner throws and the
     * worker bean never constructs, so nothing is ever swept there. It falls back to a fixed name instead.
     */
    @Test
    void aHostWhoseNameDoesNotResolveStillNamesItsLeaseOwner() {
        assertEquals("box-1", ClassifierWorker.shortHost(() -> "box-1"));
        assertEquals("host", ClassifierWorker.shortHost(() -> {
            throw new UnknownHostException("box-1");
        }));
    }
}
