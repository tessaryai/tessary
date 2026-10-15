// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the submitting thread's MDC into a pooled worker and restores the worker's prior MDC afterwards, so a
 * recycled thread never leaks one job's context. Also binds the {@code pool} Pyroscope label; without the agent
 * attached that costs one boolean check.
 */
public final class MdcTaskDecorator implements TaskDecorator {

    /** Underscore, not a dot: Pyroscope silently discards dotted label names. */
    private static final String LABEL_PREFIX = "tessary_";

    // No tenant label: decorate() runs on the submitting thread, and the drainers bind PROJECT_ID inside the task body,
    // so it would always be absent. Tenant attribution has to be bound where the context is.

    private final String poolName;

    /**
     * @param poolName stable, low-cardinality pool id; virtual threads leave no thread identity to group CPU samples by
     * otherwise
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
        // The label scope wraps the MDC set-up and tear-down too, so no sample lands outside it.
        return ProfilingLabels.wrap(Map.of(LABEL_PREFIX + "pool", poolName), mdcBound);
    }
}
