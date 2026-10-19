// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bounds how many metrics reads run at once, so a dashboard's widgets cannot take the whole read pool, and how many of
 * those one project may hold, so a busy project cannot keep every other project out. A read takes its project's permit
 * first, so a project over its cap waits on its own queue rather than in front of other projects on the shared one.
 */
@Component
public class MetricsGate {

    static final long ACQUIRE_MS = 2000;

    private final Semaphore permits;
    private final int perProject;
    /** One permit pool per project that has read since boot; projects are few, so it is never pruned. */
    private final ConcurrentHashMap<String, Semaphore> projects = new ConcurrentHashMap<>();

    private final long acquireMs;

    @Autowired
    public MetricsGate(MetricsProperties props) {
        this(props, ACQUIRE_MS);
    }

    MetricsGate(MetricsProperties props, long acquireMs) {
        this(props.maxConcurrentReads(), props.maxConcurrentReadsPerProject(), acquireMs);
    }

    MetricsGate(int permits, int perProject, long acquireMs) {
        this.permits = new Semaphore(permits, true);
        this.perProject = perProject;
        this.acquireMs = acquireMs;
    }

    /**
     * Runs work under one project permit and one shared permit; 503 BUSY when they are not both free within one wait,
     * or the caller is interrupted. Whatever was taken is given back on every path.
     */
    public <T> T withPermit(String projectId, Supplier<T> work) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(acquireMs);
        Semaphore project = projects.computeIfAbsent(projectId, id -> new Semaphore(perProject, true));
        acquire(project, deadline);
        try {
            acquire(permits, deadline);
            try {
                return work.get();
            } finally {
                permits.release();
            }
        } finally {
            project.release();
        }
    }

    private static void acquire(Semaphore pool, long deadline) {
        try {
            if (!pool.tryAcquire(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                throw new TessaryException(MetricsError.BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(MetricsError.BUSY);
        }
    }

    int available() {
        return permits.availablePermits();
    }
}
