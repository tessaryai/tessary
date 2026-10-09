// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link MdcTaskDecorator}: a task runs under the MDC its submitter had, and the running thread gets its
 * own MDC back afterwards. The task is run on the test thread, standing in for a pool thread that has
 * already run something else. Pyroscope's label API is on this module's test classpath, so the task also
 * runs through {@link ProfilingLabels}' wrapper here, as it does under the agent.
 */
class MdcTaskDecoratorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /**
     * The bug: a pool thread keeps the MDC of the task it ran before, so the next task's lines are
     * tagged with the previous tenant's project.
     */
    @Test
    void aTaskSubmittedWithoutContextSeesNoneOfTheRunningThreadsStaleKeys() {
        Runnable task = new MdcTaskDecorator("synth").decorate(() -> assertEquals(Map.of(), mdc()));
        MDC.put(LogContext.PROJECT_ID, "stale");

        task.run();

        assertEquals(Map.of(LogContext.PROJECT_ID, "stale"), mdc(), "the running thread's own MDC is restored");
    }

    /** The bug: the task logs without the submitter's job id, or leaves it behind on the pool thread. */
    @Test
    void aTaskRunsUnderItsSubmittersContextAndLeavesTheRunningThreadsIntact() {
        MDC.put(LogContext.JOB_ID, "job-1");
        Map<String, String> seen = new HashMap<>();
        Runnable task = new MdcTaskDecorator("synth").decorate(() -> seen.putAll(mdc()));
        MDC.clear();
        MDC.put(LogContext.PROJECT_ID, "runner");

        task.run();

        assertEquals(Map.of(LogContext.JOB_ID, "job-1"), seen);
        assertEquals(Map.of(LogContext.PROJECT_ID, "runner"), mdc());
    }

    private static Map<String, String> mdc() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        return copy == null ? Map.of() : copy;
    }
}
