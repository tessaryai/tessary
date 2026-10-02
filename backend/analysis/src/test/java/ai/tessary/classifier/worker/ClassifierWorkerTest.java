// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierRowBuilder;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.TestObservations;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.catalog.PagedDetector;
import ai.tessary.classifier.catalog.PagedDetector.PageAction;
import ai.tessary.classifier.catalog.PagedDetector.Status;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * The observation sweep's page loop: a drain-set kind takes pages until the stream runs out, recording each before
 * the next; other kinds take one page a claim; a drain that loses its lease stops.
 *
 * <p>Severity against the dead-letter budget: a persistent failure surfaces at ERROR through the dead-letter
 * transition, while below-cap failures stay WARN and dedup to one stacktrace per streak.
 *
 * <p>The held-page rule for a detector that scores pages against a provider: a page on which more than half of the
 * sent turns failed on an unavailable provider is held (cursor left, count bumped) and sent whole again; at or under
 * half it is written and the cursor moves; after the allowed holds it is skipped with one WARN; a pause mid-page leaves
 * the cursor, and an already-paused classifier passes the page.
 */
@ExtendWith(MockitoExtension.class)
class ClassifierWorkerTest {

    private static final String PROJECT = "proj-1";
    private static final String CLASSIFIER = "sig-1";
    private static final String KIND = BuiltInDetector.Kind.FRUSTRATION;
    private static final int PAGE = 2;
    private static final int RETRY_PAGE = 4;
    private static final int MAX_HOLDS = 3;

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

    /** The sweep port with two beans, so assertions do not depend on which concrete sweeps this build carries. */
    @Mock
    ClassifierSweep metricSweep;

    @Mock
    ClassifierSweep toolErrorSweep;

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
     * No step's failure stops the tick: a failing dead-letter sweep still enqueues and claims, and one project's
     * throwing enqueue still leaves the next enqueued.
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

    /** A failed project scan or claim ends the tick. */
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
     * A deleted or disabled classifier's job finishes without moving its cursor: left claimed it re-runs every tick,
     * and moving the cursor would skip traffic if it returns.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aJobForAMissingOrDisabledClassifierFinishesWithoutMovingItsCursor(boolean exists) {
        when(signals.findById(PROJECT, CLASSIFIER))
                .thenReturn(
                        exists
                                ? Optional.of(ClassifierRowBuilder.of(BuiltInDetector.Kind.REGEX)
                                        .disabled()
                                        .discovery()
                                        .build())
                                : Optional.empty());

        worker().sweepForTest(job());

        verify(jobs).markSwept("job-1", null, null);
        verifyNoInteractions(substrate);
    }

    /**
     * After scoring, everything is fail-soft: a failed detection write, arming gate, pre-deploy registration, or
     * catch-up costs only itself. None may stop the cursor, or the page is re-scored forever.
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

    /**
     * On a host whose name does not resolve, building the lease owner threw and the worker never constructed. It
     * falls back to a fixed name.
     */
    @Test
    void aHostWhoseNameDoesNotResolveStillNamesItsLeaseOwner() {
        assertEquals("box-1", ClassifierWorker.shortHost(() -> "box-1"));
        assertEquals("host", ClassifierWorker.shortHost(() -> {
            throw new UnknownHostException("box-1");
        }));
    }

    @Test
    void repeatedBelowCapFailuresStayWarnAndDedupToOneStacktracePerStreak() {
        ClassifierWorker worker = worker(sweeps());

        ClassifierJobRow job = job();
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
        ClassifierWorker worker = worker(sweeps());
        ClassifierJobRow job = job();
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
        ClassifierWorker worker = worker(sweeps());

        ClassifierJobRow job = job("job-te", "sig-te");
        ClassifierRow signal = ClassifierRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .id("sig-te")
                .named("tool-errors", "Tool errors")
                .build();
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
        ClassifierWorker worker = worker(sweeps());

        ClassifierJobRow job = job("job-unclaimed", "sig-unclaimed");
        ClassifierRow signal = ClassifierRowBuilder.of("unclaimed_window_kind")
                .id("sig-unclaimed")
                .named("unclaimed", "Unclaimed")
                .build();
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
        ClassifierWorker worker = worker(sweeps());

        ClassifierJobRow job = job("job-fr", "sig-fr");
        ClassifierRow signal = ClassifierRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION)
                .id("sig-fr")
                .named("frustration", "Frustration")
                .build();
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

    /** More than half the sent turns unavailable holds the page; a page held {@code maxHolds} times is skipped. */
    @ParameterizedTest
    @CsvSource({
        "SCORED,  4, 3, 0, 3, HOLD",
        "SCORED,  3, 2, 2, 3, HOLD",
        "SCORED,  4, 2, 0, 3, PERSIST",
        "SCORED,  4, 0, 0, 3, PERSIST",
        // Nothing eligible is written, which advances past it.
        "SCORED,  0, 0, 0, 3, PERSIST",
        "SCORED,  4, 4, 3, 3, SKIP",
        "SCORED,  4, 4, 0, 0, SKIP",
        "ABORTED, 4, 0, 0, 3, ABORT",
        "PAUSED,  0, 0, 0, 3, PASS"
    })
    void decideHoldsPersistsSkipsAbortsOrPasses(
            Status status, int sent, int unavailable, int holds, int maxHolds, PageAction expected) {
        assertEquals(expected, PageRetryRule.decide(page(status, sent, unavailable), holds, maxHolds));
    }

    @Test
    void aHeldPageLeavesTheCursorAndCountsTheHold() {
        FakePaged detector = armSignal(page(Status.SCORED, 4, 3));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, RETRY_PAGE)).thenReturn(window);

        worker(RETRY_PAGE).sweepForTest(job(0));

        verify(jobs).holdPage("job-1");
        verify(jobs, never()).markSwept(anyString(), any(), any());
        verify(catchUp, never()).caughtUp(any(), any(), any());
        assertEquals(List.of(PageAction.HOLD), detector.actions, "nothing is written on a held page");
    }

    @Test
    void aHeldPageIsScoredWholeAgainOnTheNextTick() {
        FakePaged detector = armSignal(page(Status.SCORED, 4, 3));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, RETRY_PAGE)).thenReturn(window);

        worker(RETRY_PAGE).sweepForTest(job(0));
        worker(RETRY_PAGE).sweepForTest(job(1));

        assertEquals(2, detector.scoredPages.size());
        assertEquals(window, detector.scoredPages.get(1), "the retry re-sends every turn, succeeded ones too");
    }

    @Test
    void theFourthFailureSkipsThePageWithOneWarnAndAdvances() {
        FakePaged detector = armSignal(page(Status.SCORED, 4, 4));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, RETRY_PAGE)).thenReturn(window);

        worker(RETRY_PAGE).sweepForTest(job(MAX_HOLDS));

        verify(jobs).markSwept("job-1", createdAt(window), handle(window));
        verify(jobs, never()).holdPage(anyString());
        assertEquals(List.of(PageAction.SKIP), detector.actions);
        long warns = appender.list.stream()
                .filter(e ->
                        e.getLevel() == Level.WARN && e.getFormattedMessage().contains("skipped a page"))
                .count();
        assertEquals(1, warns, "one WARN names the skipped page: " + appender.list);
    }

    @Test
    void aPauseMidPageLeavesTheCursorWithoutCountingAHold() {
        FakePaged detector = armSignal(page(Status.ABORTED, 4, 0));
        when(substrate.observationsAfter(PROJECT, null, null, RETRY_PAGE)).thenReturn(observations());

        worker(RETRY_PAGE).sweepForTest(job(0));

        verify(jobs).markSwept("job-1", null, null);
        verify(jobs, never()).holdPage(anyString());
        assertEquals(List.of(PageAction.ABORT), detector.actions);
    }

    @Test
    void aPausedClassifierPassesThePageAndAdvances() {
        FakePaged detector = armSignal(page(Status.PAUSED, 0, 0));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, RETRY_PAGE)).thenReturn(window);

        worker(RETRY_PAGE).sweepForTest(job(0));

        verify(jobs).markSwept(eq("job-1"), eq(createdAt(window)), eq(handle(window)));
        assertEquals(List.of(PageAction.PASS), detector.actions);
        assertTrue(detector.scoredPages.size() == 1);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private record Scored(Status status, int sent, int unavailable) implements PagedDetector.ScoredPage {}

    private static Scored page(Status status, int sent, int unavailable) {
        return new Scored(status, sent, unavailable);
    }

    /** A paged detector that returns one fixed scored page and records what the worker did with it. */
    private static final class FakePaged implements PagedDetector<Scored> {
        private final Scored result;
        final List<List<SubstrateObservation>> scoredPages = new ArrayList<>();
        final List<PageAction> actions = new ArrayList<>();

        FakePaged(Scored result) {
            this.result = result;
        }

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public Scored score(ClassifierRow signal, List<SubstrateObservation> turns) {
            scoredPages.add(List.copyOf(turns));
            return result;
        }

        @Override
        public List<FiredTurn> complete(ClassifierRow signal, Scored page, PageAction action, long durationMs) {
            actions.add(action);
            return List.of();
        }

        @Override
        public int maxPageRetries() {
            return MAX_HOLDS;
        }
    }

    private ClassifierWorker worker() {
        return worker(PAGE);
    }

    private ClassifierWorker worker(int pageSize) {
        ClassifierProperties props = new ClassifierProperties();
        props.setBatchSize(pageSize);
        return worker(props, new ClassifierSweepRegistry(TestObjectProvider.of()));
    }

    private ClassifierWorker worker(ClassifierSweepRegistry sweeps) {
        return worker(new ClassifierProperties(), sweeps);
    }

    private ClassifierWorker worker(ClassifierProperties props, ClassifierSweepRegistry sweeps) {
        return new ClassifierWorker(
                signalService,
                signals,
                jobs,
                detections,
                arming,
                substrate,
                catalog,
                preDeployChecks,
                sweeps,
                TestObjectProvider.of(catchUp),
                props,
                new TraceMdcBridge(tracer),
                new SyncTaskExecutor());
    }

    /** A registry over exactly the two sweep beans, and nothing else on the classpath. */
    private ClassifierSweepRegistry sweeps() {
        when(metricSweep.kinds())
                .thenReturn(Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT));
        when(toolErrorSweep.kinds()).thenReturn(Set.of(BuiltInDetector.Kind.TOOL_ERROR));
        return new ClassifierSweepRegistry(TestObjectProvider.of(metricSweep, toolErrorSweep));
    }

    private static ClassifierRow enabled(String kind) {
        return ClassifierRowBuilder.of(kind).discovery().build();
    }

    /** An enabled observation-grain classifier whose detector fires on nothing. */
    private void armSignal(String kind) {
        when(signals.findById(PROJECT, CLASSIFIER)).thenReturn(Optional.of(enabled(kind)));
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

    private FakePaged armSignal(Scored result) {
        when(signals.findById(PROJECT, CLASSIFIER))
                .thenReturn(Optional.of(ClassifierRowBuilder.of(KIND).build()));
        when(catalog.grainFor(KIND)).thenReturn(Grain.OBSERVATION);
        when(detections.writesDetections(KIND)).thenReturn(true);
        FakePaged detector = new FakePaged(result);
        when(catalog.detectorFor(KIND)).thenReturn(detector);
        org.mockito.Mockito.lenient().when(catchUp.kinds()).thenReturn(java.util.Set.of(KIND));
        return detector;
    }

    private static ClassifierJobRow job() {
        return job(0);
    }

    private static ClassifierJobRow job(int pageRetries) {
        return job("job-1", CLASSIFIER, pageRetries);
    }

    private static ClassifierJobRow job(String id, String classifier) {
        return job(id, classifier, 0);
    }

    private static ClassifierJobRow job(String id, String classifier, int pageRetries) {
        return new ClassifierJobRow(
                id,
                PROJECT,
                classifier,
                ClassifierJobRow.PENDING,
                null,
                null,
                null,
                null,
                0,
                null,
                "now",
                "now",
                pageRetries);
    }

    /** {@code size} observations numbered from {@code from}, in cursor order. */
    private static List<SubstrateObservation> page(int from, int size) {
        List<SubstrateObservation> out = new ArrayList<>();
        for (int i = from; i < from + size; i++) {
            out.add(TestObservations.llm(
                    "span-" + i, PROJECT, "trace-" + i, null, null, null, "ok", "2026-09-13T00:00:0" + i + "Z"));
        }
        return out;
    }

    private static List<SubstrateObservation> observations() {
        return page(0, RETRY_PAGE);
    }

    private static String createdAt(List<SubstrateObservation> page) {
        return page.get(page.size() - 1).createdAt();
    }

    private static String handle(List<SubstrateObservation> page) {
        SubstrateObservation last = page.get(page.size() - 1);
        return SubstrateReadRepository.handle(last.traceId(), last.observationId());
    }
}
