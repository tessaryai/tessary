// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.util.Map;

/**
 * Attaches Pyroscope labels to CPU samples, since virtual-thread pools leave no thread identity to group by. Optional:
 * the Pyroscope classes are {@code provided} by the agent jar, so this probes once and degrades to a no-op; an
 * unguarded linkage error here would break every async task. All Pyroscope references are isolated in
 * {@link PyroscopeLabelBinder}.
 */
public final class ProfilingLabels {

    /**
     * {@code LinkageError} is caught too: a version-skewed agent jar fails at link time, and must degrade, not crash.
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

    /**
     * Must be applied on the thread that runs the task: Pyroscope's label context is thread-scoped. Returns
     * {@code body} unchanged without the agent or labels.
     */
    public static Runnable wrap(Map<String, String> labels, Runnable body) {
        if (!AVAILABLE || labels.isEmpty()) {
            return body;
        }
        return PyroscopeLabelBinder.wrap(labels, body);
    }
}
