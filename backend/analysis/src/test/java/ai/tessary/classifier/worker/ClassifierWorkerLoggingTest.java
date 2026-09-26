// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
 * Severity against the dead-letter budget: a persistent failure surfaces at ERROR through the dead-letter transition,
 * while below-cap failures stay WARN and dedup to one stacktrace per streak.
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

    /** The sweep port with two beans, so assertions do not depend on which concrete sweeps this build carries. */
    @Mock
    ClassifierSweep metricSweep;

    @Mock
    ClassifierSweep toolErrorSweep;

    private ClassifierSweepRegistry sweeps;

    @Mock
    Tracer tracer;

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void buildRegistry() {
        when(metricSweep.kinds())
                .thenReturn(Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT));
        when(toolErrorSweep.kinds()).thenReturn(Set.of(BuiltInDetector.Kind.TOOL_ERROR));
        sweeps = registryOf(metricSweep, toolErrorSweep);
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
                TestObjectProvider.of(),
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-1", "proj-1", "sig-1", ClassifierJobRow.PENDING, null, null, null, null, 0, null, "now", "now", 0);
        when(signals.findById("proj-1", "sig-1")).thenThrow(new RuntimeException("boom"));
        when(jobs.markFailed(eq("job-1"), any(), anyInt())).thenReturn(false); // inside the budget

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
    /** A streak past the summary interval says it is still failing, with its count; otherwise it looks recovered. */
    @Test
    void aStreakThatOutlastsTheSummaryIntervalSaysItIsStillFailingWithItsCount() {
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
                TestObjectProvider.of(),
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());
        ClassifierJobRow job = new ClassifierJobRow(
                "job-1", "proj-1", "sig-1", ClassifierJobRow.PENDING, null, null, null, null, 0, null, "now", "now", 0);
        when(signals.findById("proj-1", "sig-1")).thenThrow(new RuntimeException("boom"));
        when(jobs.markFailed(eq("job-1"), any(), anyInt())).thenReturn(false);

        for (int i = 0; i < 30; i++) worker.sweepForTest(job);

        List<Map<String, Object>> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(e -> e.getKeyValuePairs().stream()
                        .collect(java.util.stream.Collectors.toMap(kv -> kv.key, kv -> (Object) kv.value)))
                .toList();
        assertEquals(2, warns.size(), "the streak's first failure, then one summary at the thirtieth");
        assertEquals("signal.sweep.still-failing", warns.get(1).get("event"));
        assertEquals(30L, warns.get(1).get("occurrences"));
    }

    /**
     * WINDOW grain falls through to {@link MetricDriftSweep}. {@code tool_error} once took that fallthrough and kept
     * a duplicate copy of every duration and cost baseline under its own id.
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
                TestObjectProvider.of(),
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-te",
                "proj-1",
                "sig-te",
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
    }

    /**
     * A fitting-tier kind with no registered sweep is inert: one WARN, the job completes. It must not throw (burning
     * the dead-letter budget every heartbeat), retire the classifier (permanent), or fall through to another sweep.
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
                registryOf(metricSweep, toolErrorSweep),
                TestObjectProvider.of(),
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-unclaimed",
                "proj-1",
                "sig-unclaimed",
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
        ClassifierRow signal = new ClassifierRow(
                "sig-unclaimed",
                "proj-1",
                "unclaimed",
                "Unclaimed",
                null,
                "unclaimed_window_kind",
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                "now",
                "now");
        when(signals.findById("proj-1", "sig-unclaimed")).thenReturn(Optional.of(signal));
        when(catalog.grainFor("unclaimed_window_kind")).thenReturn(Grain.WINDOW);

        worker.sweepForTest(job);

        assertTrue(
                appender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN
                                && e.getFormattedMessage().contains("no classifier sweep is registered")),
                "an unregistered kind must be loud, not silent: " + appender.list);
        assertTrue(
                appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
                "it is not a failure either — no dead letter, no burnt attempt budget");
        verify(jobs).markSwept("job-unclaimed", null, null);
        verify(metricSweep, never()).sweep(any());
        verify(toolErrorSweep, never()).sweep(any());
    }

    /**
     * The observation-grain twin: a detector with no {@code DetectionTable} on the classpath must not score. One
     * WARN, the job completes, and nothing is touched.
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
                TestObjectProvider.of(),
                new ClassifierProperties(),
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());

        ClassifierJobRow job = new ClassifierJobRow(
                "job-fr",
                "proj-1",
                "sig-fr",
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
