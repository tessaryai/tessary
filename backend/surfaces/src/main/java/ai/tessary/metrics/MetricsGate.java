// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounds how many metrics reads run at once, so a dashboard's widgets cannot take the whole read pool. */
@Component
public class MetricsGate {

    static final long ACQUIRE_MS = 2000;

    private final Semaphore permits;
    private final long acquireMs;

    @Autowired
    public MetricsGate(MetricsProperties props) {
        this(props.maxConcurrentReads(), ACQUIRE_MS);
    }

    MetricsGate(int permits, long acquireMs) {
        this.permits = new Semaphore(permits, true);
        this.acquireMs = acquireMs;
    }

    /** Runs work under one permit; 503 BUSY when none frees within the wait or the caller is interrupted. */
    public <T> T withPermit(Supplier<T> work) {
        try {
            if (!permits.tryAcquire(acquireMs, TimeUnit.MILLISECONDS)) throw new TessaryException(MetricsError.BUSY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(MetricsError.BUSY);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    int available() {
        return permits.availablePermits();
    }
}
