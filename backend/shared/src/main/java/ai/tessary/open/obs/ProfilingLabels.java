// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.util.Map;

/**
 * Attaches Pyroscope labels to the CPU samples taken while a task runs, so a flame graph can be
 * sliced by the workload that produced it rather than only by method.
 *
 * <p><b>Why this exists.</b> Host and JVM metrics can attribute CPU no further than "the backend
 * process". A flame graph gets to "which method" — but on this codebase that is still not enough:
 * almost every pool in {@code AsyncConfig} is a {@code SimpleAsyncTaskExecutor} on <i>virtual</i>
 * threads, so there is no pool thread, no bounded queue, and no stable thread identity to group by.
 * Micrometer's {@code executor_*} binders do not attach to those executors at all. Labels are the
 * replacement axis: they turn "{@code JsonParser.nextToken} is hot" into "{@code
 * JsonParser.nextToken} is hot <i>on the synth pool, for project X</i>", which is the difference
 * between a curiosity and a diagnosis.
 *
 * <p><b>Optional by construction.</b> The Pyroscope classes live in the {@code -javaagent} jar on
 * the system classpath and are declared {@code provided} in the build, so they are absent whenever
 * the agent is not attached — unit tests, plain {@code mvn spring-boot:run}, and any deployment
 * with the profiler disabled. This class therefore probes once and degrades to a no-op wrapper
 * rather than failing. That matters more than it looks: the decorator wraps <i>every async task in
 * the platform</i>, so an unguarded {@code NoClassDefFoundError} here would take down observer
 * triage, synthesis, grading, and alert delivery at once.
 *
 * <p>All real Pyroscope references are isolated in {@link PyroscopeLabelBinder} so that class is
 * only ever loaded when the agent is present; this one stays linkable on its own.
 */
public final class ProfilingLabels {

    /**
     * Resolved once at class-init. {@code LinkageError} is caught alongside the expected
     * {@code ClassNotFoundException} because a version-skewed agent jar (labels API present but
     * incompatible) fails at link time, not lookup time — and that must degrade, not crash.
     */
    private static final boolean AVAILABLE = probe();

    private ProfilingLabels() {}

    private static boolean probe() {
        try {
            Class.forName(
                    "io.pyroscope.labels.v2.Pyroscope$LabelsWrapper", false, ProfilingLabels.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Whether the Pyroscope agent's labels API is on the classpath. */
    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Returns {@code body} wrapped so that CPU samples taken during its execution carry {@code
     * labels}; returns {@code body} unchanged when the agent is absent or no labels were supplied.
     *
     * <p>The wrapper must be applied on the thread that <i>runs</i> the task, not the one that
     * submits it: Pyroscope's label context is thread-scoped, and with virtual threads the running
     * thread is created per task. {@link MdcTaskDecorator} is exactly that seam.
     */
    public static Runnable wrap(Map<String, String> labels, Runnable body) {
        if (!AVAILABLE || labels.isEmpty()) {
            return body;
        }
        return PyroscopeLabelBinder.wrap(labels, body);
    }
}
