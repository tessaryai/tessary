// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.catalog.PagedDetector;
import ai.tessary.classifier.catalog.PagedDetector.PageAction;
import ai.tessary.classifier.catalog.PagedDetector.Status;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * The held-page rule for a detector that scores pages against a provider: a page on which more than
 * half of the sent turns failed on an unavailable provider is held (cursor left, count bumped) and sent
 * whole again; at or under half it is written and the cursor moves; after the allowed holds it is
 * skipped with one WARN; a pause mid-page leaves the cursor, and an already-paused classifier passes
 * the page.
 */
@ExtendWith(MockitoExtension.class)
class ClassifierWorkerPageRetryTest {

    private static final String PROJECT = "proj-1";
    private static final String CLASSIFIER = "sig-1";
    private static final String KIND = BuiltInDetector.Kind.FRUSTRATION;
    private static final int PAGE = 4;
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

    // ---- the rule ---------------------------------------------------------------------------------

    @Test
    void decide_holdsAPageWhereMoreThanHalfOfTheSentTurnsWereUnavailable() {
        assertEquals(PageAction.HOLD, PageRetryRule.decide(page(Status.SCORED, 4, 3), 0, MAX_HOLDS));
        assertEquals(PageAction.HOLD, PageRetryRule.decide(page(Status.SCORED, 3, 2), 2, MAX_HOLDS));
    }

    @Test
    void decide_persistsAPageAtOrUnderHalf() {
        assertEquals(PageAction.PERSIST, PageRetryRule.decide(page(Status.SCORED, 4, 2), 0, MAX_HOLDS));
        assertEquals(PageAction.PERSIST, PageRetryRule.decide(page(Status.SCORED, 4, 0), 0, MAX_HOLDS));
        assertEquals(
                PageAction.PERSIST,
                PageRetryRule.decide(page(Status.SCORED, 0, 0), 0, MAX_HOLDS),
                "a page with nothing eligible is written, which advances past it");
    }

    @Test
    void decide_skipsThePageOnceItWasHeldAsOftenAsAllowed() {
        assertEquals(PageAction.SKIP, PageRetryRule.decide(page(Status.SCORED, 4, 4), MAX_HOLDS, MAX_HOLDS));
        assertEquals(PageAction.SKIP, PageRetryRule.decide(page(Status.SCORED, 4, 4), 0, 0));
    }

    @Test
    void decide_abortsOnAPauseMidPageAndPassesWhenAlreadyPaused() {
        assertEquals(PageAction.ABORT, PageRetryRule.decide(page(Status.ABORTED, 4, 0), 0, MAX_HOLDS));
        assertEquals(PageAction.PASS, PageRetryRule.decide(page(Status.PAUSED, 0, 0), 0, MAX_HOLDS));
    }

    // ---- the worker applying it -------------------------------------------------------------------

    @Test
    void aHeldPageLeavesTheCursorAndCountsTheHold() {
        FakePaged detector = armSignal(page(Status.SCORED, 4, 3));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(window);

        worker().sweepForTest(job(0));

        verify(jobs).holdPage("job-1");
        verify(jobs, never()).markSwept(anyString(), any(), any());
        verify(catchUp, never()).caughtUp(any(), any(), any());
        assertEquals(List.of(PageAction.HOLD), detector.actions, "nothing is written on a held page");
    }

    @Test
    void aHeldPageIsScoredWholeAgainOnTheNextTick() {
        FakePaged detector = armSignal(page(Status.SCORED, 4, 3));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(window);

        worker().sweepForTest(job(0));
        worker().sweepForTest(job(1));

        assertEquals(2, detector.scoredPages.size());
        assertEquals(window, detector.scoredPages.get(1), "the retry re-sends every turn, succeeded ones too");
    }

    @Test
    void aPageAtOrUnderHalfIsWrittenAndTheCursorMoves() {
        FakePaged detector = armSignal(page(Status.SCORED, 4, 2));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(window);

        worker().sweepForTest(job(2));

        verify(jobs).markSwept("job-1", createdAt(window), handle(window));
        verify(jobs, never()).holdPage(anyString());
        assertEquals(List.of(PageAction.PERSIST), detector.actions);
    }

    @Test
    void theFourthFailureSkipsThePageWithOneWarnAndAdvances() {
        FakePaged detector = armSignal(page(Status.SCORED, 4, 4));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(window);

        worker().sweepForTest(job(MAX_HOLDS));

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
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(observations());

        worker().sweepForTest(job(0));

        verify(jobs).markSwept("job-1", null, null);
        verify(jobs, never()).holdPage(anyString());
        assertEquals(List.of(PageAction.ABORT), detector.actions);
    }

    @Test
    void aPausedClassifierPassesThePageAndAdvances() {
        FakePaged detector = armSignal(page(Status.PAUSED, 0, 0));
        List<SubstrateObservation> window = observations();
        when(substrate.observationsAfter(PROJECT, null, null, PAGE)).thenReturn(window);

        worker().sweepForTest(job(0));

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
        ClassifierProperties props = new ClassifierProperties();
        props.setBatchSize(PAGE);
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

    private FakePaged armSignal(Scored result) {
        when(signals.findById(PROJECT, CLASSIFIER))
                .thenReturn(Optional.of(new ClassifierRow(
                        CLASSIFIER,
                        PROJECT,
                        KIND,
                        KIND,
                        null,
                        KIND,
                        null,
                        true,
                        1,
                        true,
                        ClassifierRow.Mode.TRACKING,
                        "now",
                        "now")));
        when(catalog.grainFor(KIND)).thenReturn(Grain.OBSERVATION);
        when(detections.writesDetections(KIND)).thenReturn(true);
        FakePaged detector = new FakePaged(result);
        when(catalog.detectorFor(KIND)).thenReturn(detector);
        org.mockito.Mockito.lenient().when(catchUp.kinds()).thenReturn(java.util.Set.of(KIND));
        return detector;
    }

    private static ClassifierJobRow job(int pageRetries) {
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
                pageRetries);
    }

    private static List<SubstrateObservation> observations() {
        List<SubstrateObservation> out = new ArrayList<>();
        for (int i = 0; i < PAGE; i++) {
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
}
