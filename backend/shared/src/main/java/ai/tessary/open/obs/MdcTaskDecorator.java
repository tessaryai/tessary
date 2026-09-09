// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the submitting thread's MDC into a pooled worker thread so logs
 * emitted on the worker carry the parent's business context (runId/projectId),
 * and tags the worker's CPU samples with the pool and tenant that caused them.
 *
 * <p>Attached to the queue-drainer pools ({@code observerTaskExecutor},
 * {@code synthTaskExecutor}): the submitting thread already has its
 * {@link LogContext} bound when it hands work off, and without this the logs
 * emitted on the worker threads would lose that context. Snapshots and restores
 * the worker's prior MDC so a recycled pool thread never leaks one job's context
 * into the next.
 *
 * <p><b>Profiling labels.</b> This decorator is the one seam every async task in the platform
 * already passes through on the thread that will actually run it, which makes it the natural — and
 * cheapest — place to bind Pyroscope labels. Doing so is what lets a CPU flame graph be sliced by
 * pool and tenant instead of only by method; see {@link ProfilingLabels} for why that axis is the
 * only meaningful one once the pools run on virtual threads. When the profiling agent is not
 * attached the wrapping is a no-op and costs a single boolean check.
 */
public final class MdcTaskDecorator implements TaskDecorator {

    /**
     * Namespace for every label this decorator emits, so ours are filterable as a group and can
     * never collide with the labels Pyroscope and the agent add themselves ({@code service_name},
     * {@code __profile_type__}, {@code process.runtime.*}, and so on).
     *
     * <p>Underscore, not a dot: Pyroscope stores Prometheus-style label names and <b>silently
     * discards</b> dotted ones. The agent itself sends {@code process.runtime.*} /
     * {@code otel.scope.*} on every upload and none of them are ever stored.
     */
    private static final String LABEL_PREFIX = "tessary_";

    /*
     * WHY THERE IS NO TENANT LABEL HERE.
     *
     * An earlier revision also promoted projectId/orgId from the MDC. It could never work, and
     * the reason is a binding-order trap worth stating so nobody re-adds it:
     *
     *   decorate() runs on the SUBMITTING thread, so the MDC it captures is whatever the
     *   submitter had bound. Every queue drainer submits a bare job reference and binds the
     *   business context INSIDE the task body — e.g. ClassifierWorker executes `() -> sweep(job)`
     *   and only then does sweep() put PROJECT_ID. At capture time those keys do not exist yet.
     *   (orgId was doubly dead: LogContext.ORG_ID has zero call sites in backend/app.)
     *
     * The result was a label that was always absent, which is worse than no label — an empty
     * tenant filter reads as "profiling is broken" rather than "never populated".
     *
     * Tenant attribution is still worth having, but it has to be bound where the context is,
     * not where the task is handed off — i.e. a hook at the LogContext level, or an explicit
     * ProfilingLabels.wrap inside each worker. That is a separate change with its own
     * cardinality and overhead review, not a tweak to this list.
     */

    private final String poolName;

    /**
     * @param poolName stable, low-cardinality identifier for the pool this decorator serves (e.g.
     *     {@code "synth"}). Becomes the {@code pool} profiling label — the primary axis for
     *     answering "which background workload burned the CPU", which is otherwise unanswerable
     *     because virtual threads leave no durable thread identity to group by.
     */
    public MdcTaskDecorator(String poolName) {
        this.poolName = poolName;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        Runnable mdcBound = () -> {
            Map<String, String> prior = MDC.getCopyOfContextMap();
            if (submitted != null) {
                MDC.setContextMap(submitted);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                if (prior != null) {
                    MDC.setContextMap(prior);
                } else {
                    MDC.clear();
                }
            }
        };
        // Wrapping OUTSIDE the MDC-binding runnable is intentional: the label scope must cover the
        // whole execution, including the MDC set-up and tear-down, so no sample lands outside it.
        return ProfilingLabels.wrap(Map.of(LABEL_PREFIX + "pool", poolName), mdcBound);
    }
}
