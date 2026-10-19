// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricsGateTest {

    /** The bug: a dashboard's widgets queue without bound and take the whole read pool from the rest of the app. */
    @Test
    @DisplayName("a caller from another project is busy while the only permit is held")
    void theSecondCallerIsBusyWhileOnePermitIsHeld() throws Exception {
        MetricsGate gate = new MetricsGate(1, 1, 10);
        Held first = hold(gate, "p1");

        TessaryException busy = assertThrows(TessaryException.class, () -> gate.withPermit("p2", () -> "second"));
        assertEquals(MetricsError.BUSY, busy.error());
        assertEquals("Metrics is busy. Other reads are running; try again in a moment.", busy.getMessage());

        first.finish();
        assertEquals("third", gate.withPermit("p2", () -> "third"));
        assertEquals(1, gate.available());
    }

    /** The bug: one busy project holds every permit, and every other project's Metrics answers busy. */
    @Test
    @DisplayName("one project holds at most its cap, and another project still gets in")
    void oneProjectHoldsAtMostItsCapAndAnotherProjectStillGetsIn() throws Exception {
        MetricsGate gate = new MetricsGate(3, 2, 10);
        List<Held> held = new ArrayList<>();
        for (int i = 0; i < 2; i++) held.add(hold(gate, "busy"));

        TessaryException busy = assertThrows(TessaryException.class, () -> gate.withPermit("busy", () -> "third"));
        assertEquals(MetricsError.BUSY, busy.error());
        assertEquals("quiet", gate.withPermit("quiet", () -> "quiet"));

        for (Held h : held) h.finish();
        assertEquals("again", gate.withPermit("busy", () -> "again"));
        assertEquals(3, gate.available());
    }

    /** The bug: a read refused at the shared gate keeps its project's permit, so the project shrinks for good. */
    @Test
    @DisplayName("a read refused for want of a shared permit gives its project permit back")
    void aReadRefusedAtTheSharedGateGivesItsProjectPermitBack() throws Exception {
        MetricsGate gate = new MetricsGate(2, 2, 10);
        List<Held> others = new ArrayList<>();
        for (int i = 0; i < 2; i++) others.add(hold(gate, "other"));

        TessaryException busy = assertThrows(TessaryException.class, () -> gate.withPermit("mine", () -> "never"));
        assertEquals(MetricsError.BUSY, busy.error());
        for (Held h : others) h.finish();

        List<Held> mine = new ArrayList<>();
        for (int i = 0; i < 2; i++) mine.add(hold(gate, "mine"));
        for (Held h : mine) h.finish();
        assertEquals(2, gate.available());
    }

    /** The bug: a failing statement leaks its permits, and after enough failures Metrics answers busy forever. */
    @Test
    @DisplayName("both permits are released when the work throws")
    void thePermitIsReleasedWhenWorkThrows() {
        MetricsGate gate = new MetricsGate(1, 1, 10);
        IllegalStateException boom = new IllegalStateException("boom");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> gate.withPermit("p", () -> {
                    throw boom;
                }));

        assertEquals(boom, thrown);
        assertEquals(1, gate.available());
        assertEquals("again", gate.withPermit("p", () -> "again"));
    }

    /** The bug: an interrupt swallowed while waiting, so a shutting-down request thread keeps running. */
    @Test
    @DisplayName("an interrupted caller is busy, keeps its interrupt flag and holds no permit")
    void anInterruptedCallerIsBusyAndKeepsItsInterruptFlag() {
        MetricsGate gate = new MetricsGate(1, 1, 10_000);
        Thread.currentThread().interrupt();
        try {
            TessaryException busy = assertThrows(TessaryException.class, () -> gate.withPermit("p", () -> "never"));
            assertEquals(MetricsError.BUSY, busy.error());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(1, gate.available());
        assertEquals("after", gate.withPermit("p", () -> "after"));
    }

    /** The bug: the configured caps ignored, so an operator cannot widen or narrow either gate. */
    @Test
    @DisplayName("the properties size the shared permits and the per-project cap, and the wait is two seconds")
    void theSpringConstructorSizesPermitsFromProperties() throws Exception {
        assertEquals(5, new MetricsGate(new MetricsProperties(8000, 5, 1, "64MB")).available());
        assertEquals(2000, MetricsGate.ACQUIRE_MS);

        MetricsGate gate = new MetricsGate(new MetricsProperties(8000, 5, 1, "64MB"), 10);
        Held one = hold(gate, "p");
        assertThrows(TessaryException.class, () -> gate.withPermit("p", () -> "second"));
        assertEquals("q", gate.withPermit("q", () -> "q"));
        one.finish();
    }

    /** A read that holds its permits until {@link #finish()}. */
    private record Held(CountDownLatch release, CompletableFuture<String> done) {
        void finish() throws Exception {
            release.countDown();
            assertEquals("held", done.get(5, TimeUnit.SECONDS));
        }
    }

    private static Held hold(MetricsGate gate, String projectId) throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> done = CompletableFuture.supplyAsync(() -> gate.withPermit(projectId, () -> {
            holding.countDown();
            await(release);
            return "held";
        }));
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        return new Held(release, done);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
