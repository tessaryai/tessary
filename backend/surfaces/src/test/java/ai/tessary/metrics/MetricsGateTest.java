// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricsGateTest {

    /** The bug: a dashboard's widgets queue without bound and take the whole read pool from the rest of the app. */
    @Test
    @DisplayName("a second caller is busy while the only permit is held")
    void theSecondCallerIsBusyWhileOnePermitIsHeld() throws Exception {
        MetricsGate gate = new MetricsGate(1, 10);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> gate.withPermit(() -> {
            holding.countDown();
            await(release);
            return "first";
        }));
        assertTrue(holding.await(5, TimeUnit.SECONDS));

        TessaryException busy = assertThrows(TessaryException.class, () -> gate.withPermit(() -> "second"));
        assertEquals(MetricsError.BUSY, busy.error());
        assertEquals("Metrics is busy. Other reads are running; try again in a moment.", busy.getMessage());

        release.countDown();
        assertEquals("first", holder.get(5, TimeUnit.SECONDS));
        assertEquals("third", gate.withPermit(() -> "third"));
        assertEquals(1, gate.available());
    }

    /** The bug: a failing statement leaks its permit, and after three failures Metrics answers busy forever. */
    @Test
    @DisplayName("the permit is released when the work throws")
    void thePermitIsReleasedWhenWorkThrows() {
        MetricsGate gate = new MetricsGate(1, 10);
        IllegalStateException boom = new IllegalStateException("boom");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> gate.withPermit(() -> {
                    throw boom;
                }));

        assertEquals(boom, thrown);
        assertEquals(1, gate.available());
    }

    /** The bug: an interrupt swallowed while waiting, so a shutting-down request thread keeps running. */
    @Test
    @DisplayName("an interrupted caller is busy and keeps its interrupt flag")
    void anInterruptedCallerIsBusyAndKeepsItsInterruptFlag() {
        MetricsGate gate = new MetricsGate(1, 10_000);
        Thread.currentThread().interrupt();
        try {
            TessaryException busy = assertThrows(TessaryException.class, () -> gate.withPermit(() -> "never"));
            assertEquals(MetricsError.BUSY, busy.error());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(1, gate.available());
    }

    /** The bug: the configured max-concurrent-reads ignored, so an operator cannot widen or narrow the gate. */
    @Test
    @DisplayName("the Spring constructor sizes its permits from the properties and waits two seconds")
    void theSpringConstructorSizesPermitsFromProperties() {
        assertEquals(5, new MetricsGate(new MetricsProperties(8000, 5, "64MB")).available());
        assertEquals(2000, MetricsGate.ACQUIRE_MS);
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
