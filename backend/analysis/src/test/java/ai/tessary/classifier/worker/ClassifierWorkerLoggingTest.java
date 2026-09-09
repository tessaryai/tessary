// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.gate.PreDeployCheckService;
import ai.tessary.open.obs.LogContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Covers the severity policy (gh#532) as it composes with the dead-letter budget (gh#531): a
 * persistent sweep failure must surface at ERROR — via the budget-exhausted dead-letter
 * transition — so an error-rate query keyed on {@code level="ERROR"} actually sees it, while
 * below-cap failures stay WARN and dedup to one stacktrace per streak.
 */
@ExtendWith(MockitoExtension.class)
class ClassifierWorkerLoggingTest {

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

    /**
     * The four sweeps as the worker now sees them: one port, four beans, no concrete type named.
     *
     * <p>This is not cosmetic. The enforcer bans an open module from declaring a dependency on a paid
     * jar at {@code validate}, test scope included, so mocking the CONCRETE {@code BehaviorDriftSweep}
     * and {@code ConformanceSweep} — which is what this test used to do — is a coupling that cannot be
     * re-pointed when those classes move behind the boundary, only deleted. Mocking the port keeps every
     * assertion below.
     */
    @Mock
    ClassifierSweep behaviorSweep;

    @Mock
    ClassifierSweep metricSweep;

    @Mock
    ClassifierSweep conformanceSweep;

    @Mock
    ClassifierSweep toolErrorSweep;

    private ClassifierSweepRegistry sweeps;

    @Mock
    Tracer tracer;

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void buildRegistry() {
        when(behaviorSweep.kinds()).thenReturn(Set.of(BuiltInDetector.Kind.BEHAVIOR_DRIFT));
        when(metricSweep.kinds())
                .thenReturn(Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT));
        when(conformanceSweep.kinds()).thenReturn(Set.of(BuiltInDetector.Kind.SOP_CONFORMANCE));
        when(toolErrorSweep.kinds()).thenReturn(Set.of(BuiltInDetector.Kind.TOOL_ERROR));
        sweeps = registryOf(behaviorSweep, metricSweep, conformanceSweep, toolErrorSweep);
    }

    /** A registry over exactly these beans, and nothing else on the classpath. */
    private static ClassifierSweepRegistry registryOf(ClassifierSweep... sweeps) {
        return new ClassifierSweepRegistry(TestObjectProvider.of(sweeps));
    }

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
    void persistentSweepFailureSurfacesAtErrorViaTheDeadLetterTransition() {
        ClassifierWorker worker = new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                sweeps,
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-1", "proj-1", "sig-1", ClassifierJobRow.PENDING, null, null, null, null, 0, null, "now", "now");
        when(signals.findById("proj-1", "sig-1")).thenThrow(new RuntimeException("boom"));
        when(jobs.markFailed(eq("job-1"), any(), anyInt())).thenReturn(true); // budget exhausted

        worker.sweepForTest(job);

        List<ILoggingEvent> events = appender.list;
        assertTrue(
                events.stream()
                        .anyMatch(e -> e.getLevel() == Level.ERROR
                                && e.getFormattedMessage().contains("dead-lettered")),
                "exhausting the failure budget must log at ERROR: " + events);
    }

    @Test
    void repeatedBelowCapFailuresStayWarnAndDedupToOneStacktracePerStreak() {
        ClassifierWorker worker = new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                sweeps,
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-1", "proj-1", "sig-1", ClassifierJobRow.PENDING, null, null, null, null, 0, null, "now", "now");
        when(signals.findById("proj-1", "sig-1")).thenThrow(new RuntimeException("boom"));
        when(jobs.markFailed(eq("job-1"), any(), anyInt())).thenReturn(false); // still inside the budget

        worker.sweepForTest(job);
        worker.sweepForTest(job);
        worker.sweepForTest(job);

        long errorCount =
                appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
        long warnCount =
                appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
        assertTrue(errorCount == 0, "a below-cap unit failure never logs ERROR (that's the dead-letter's)");
        assertTrue(warnCount == 1, "retries of the same failing job dedup to a single WARN, not one per tick");
    }

    /**
     * Covers the trace-correlation gap (gh#532 item 2): {@code tick()} must bind the scheduler
     * thread's current span into MDC before dispatching any sweep, so a background log line has a
     * trace_id to pivot from in Grafana.
     */
    @Test
    void tickBindsTheCurrentTraceBeforeDispatchingWork() {
        TraceMdcBridge mockBridge = mock(TraceMdcBridge.class);
        when(mockBridge.bindCurrentTrace()).thenReturn(LogContext.put(Map.of()));
        when(jobs.failExhausted(anyInt())).thenReturn(0);
        when(substrate.projectsWithObservations()).thenReturn(List.of());

        ClassifierWorker worker = new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                sweeps,
                new ClassifierProperties(),
                mockBridge,
                new SyncTaskExecutor());

        worker.tick();

        verify(mockBridge).bindCurrentTrace();
    }

    /**
     * The WINDOW grain is shared by three families and split on the detector kind, with
     * {@link MetricDriftSweep} as the fallthrough — so a classifier that reaches this branch without a
     * case of its own runs the metric sweep instead of its own.
     *
     * <p>That is not hypothetical for {@code tool_error}: it was declared WINDOW once before, fell
     * through, and — because its config blob names no {@code measures}, so {@code MetricDriftConfig}
     * fell back to the full default set — maintained a second copy of every duration and cost baseline
     * and emitted a duplicate finding per drift under its own signal id. Nothing failed loudly. This
     * pins the fork itself rather than the comment describing it.
     */
    @Test
    void aToolErrorJobRunsItsOwnSweepAndNeverTheMetricFallthrough() {
        ClassifierWorker worker = new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                sweeps,
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-te", "proj-1", "sig-te", ClassifierJobRow.PENDING, null, null, null, null, 0, null, "now", "now");
        ClassifierRow signal = new ClassifierRow(
                "sig-te",
                "proj-1",
                "tool-errors",
                "Tool errors",
                null,
                BuiltInDetector.Kind.TOOL_ERROR,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                "now",
                "now");
        SweepContext expected = new SweepContext(job, signal);
        when(signals.findById("proj-1", "sig-te")).thenReturn(Optional.of(signal));
        when(catalog.grainFor(BuiltInDetector.Kind.TOOL_ERROR)).thenReturn(Grain.WINDOW);
        when(toolErrorSweep.sweep(expected)).thenReturn(new SweepOutcome(0, 2));

        worker.sweepForTest(job);

        verify(toolErrorSweep).sweep(expected);
        verify(metricSweep, never()).sweep(any());
        verify(conformanceSweep, never()).sweep(any());
        verify(behaviorSweep, never()).sweep(any());
    }

    /**
     * The contract the registry replaced the fallthrough with: a fitting-tier kind nothing is registered
     * for is INERT. One WARN, the job completes, and — the part that matters — no other sweep runs in
     * its place.
     *
     * <p>This is the open edition's normal state for the two paid classifiers dispatched through {@code
     * ClassifierSweep} (behaviour drift and SOP conformance — frustration and groundedness are the other
     * two paid classifiers, #887/#888, but neither is trace/window-grain and neither ever reaches this
     * registry), so "inert" has to be a
     * first-class ending rather than an error path. Three endings are ruled out at once. It must not
     * THROW, because the job is re-pended by every heartbeat and would burn the dead-letter budget of a
     * project whose only fault is its edition. It must not RETIRE the classifier: absence of a sweep says
     * nothing about catalog membership, and leaving the catalog is permanent
     * ({@code ClassifierService#retireDroppedBuiltIns}). And it must not fall through to another sweep,
     * which is the bug the arm this replaced actually had.
     */
    @Test
    void aWindowKindWithNoRegisteredSweepIsInertAndSaysSo() {
        ClassifierWorker worker = new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                // The open edition: the two ClassifierSweep-dispatched paid classifiers (behaviour
                // drift, SOP conformance) are absent from the classpath entirely.
                registryOf(metricSweep, toolErrorSweep),
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-sop",
                "proj-1",
                "sig-sop",
                ClassifierJobRow.PENDING,
                null,
                null,
                null,
                null,
                0,
                null,
                "now",
                "now");
        ClassifierRow signal = new ClassifierRow(
                "sig-sop",
                "proj-1",
                "sop-conformance",
                "SOP conformance",
                null,
                BuiltInDetector.Kind.SOP_CONFORMANCE,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                "now",
                "now");
        when(signals.findById("proj-1", "sig-sop")).thenReturn(Optional.of(signal));
        when(catalog.grainFor(BuiltInDetector.Kind.SOP_CONFORMANCE)).thenReturn(Grain.WINDOW);

        worker.sweepForTest(job);

        assertTrue(
                appender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("no classifier sweep is registered")),
                "an unregistered kind must be loud, not silent: " + appender.list);
        assertTrue(
                appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
                "it is not a failure either — no dead letter, no burnt attempt budget");
        verify(jobs).markSwept("job-sop", null, null);
        verify(metricSweep, never()).sweep(any());
        verify(toolErrorSweep, never()).sweep(any());
    }

    /**
     * The observation-grain twin of the test above (#1071): a kind whose detector exists but whose
     * {@code DetectionTable} is not on the classpath has nowhere to write a fired row, so the worker must
     * not score at all. One WARN, the job completes, nothing throws, and neither the detector nor the
     * substrate is ever touched.
     */
    @Test
    void anObservationKindWithNoRegisteredTableIsInertAndSaysSo() {
        ClassifierWorker worker = new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                sweeps,
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-fr", "proj-1", "sig-fr", ClassifierJobRow.PENDING, null, null, null, null, 0, null, "now", "now");
        ClassifierRow signal = new ClassifierRow(
                "sig-fr",
                "proj-1",
                "frustration",
                "Frustration",
                null,
                BuiltInDetector.Kind.FRUSTRATION,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                "now",
                "now");
        when(signals.findById("proj-1", "sig-fr")).thenReturn(Optional.of(signal));
        when(catalog.grainFor(BuiltInDetector.Kind.FRUSTRATION)).thenReturn(Grain.TURN);
        when(detections.writesDetections(BuiltInDetector.Kind.FRUSTRATION)).thenReturn(false);

        assertDoesNotThrow(() -> worker.sweepForTest(job));

        long noTableWarns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("no detection table is registered"))
                .count();
        assertEquals(1, noTableWarns, "exactly one WARN for the missing table: " + appender.list);
        assertTrue(
                appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
                "not a failure: no dead letter, no burnt attempt budget");
        verify(jobs).markSwept("job-fr", null, null);
        verify(catalog, never()).detectorFor(any());
        verifyNoInteractions(substrate);
        verify(detections).writesDetections(BuiltInDetector.Kind.FRUSTRATION);
        verifyNoMoreInteractions(detections);
    }
}
