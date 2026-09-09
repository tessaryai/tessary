// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import io.pyroscope.labels.v2.LabelsSet;
import io.pyroscope.labels.v2.Pyroscope;
import java.util.Map;

/**
 * The only place in the codebase that references Pyroscope types.
 *
 * <p>Deliberately a separate class from {@link ProfilingLabels}: keeping every {@code io.pyroscope}
 * symbol behind this boundary means the JVM only ever attempts to load and link it after
 * {@code ProfilingLabels} has confirmed the agent is attached. Inlining these two calls into the
 * guard would put the same symbols in the guard's own constant pool and reintroduce the
 * {@code NoClassDefFoundError} the guard exists to prevent.
 *
 * <p>Package-private — callers go through {@link ProfilingLabels}.
 */
final class PyroscopeLabelBinder {

    private PyroscopeLabelBinder() {}

    /**
     * The {@link LabelsSet} is built once at wrap time (cheap, and shared across every execution of
     * the returned {@code Runnable}), while {@code LabelsWrapper.run} binds it to the thread-local
     * label context for the duration of the call and unbinds it afterwards — including on an
     * exceptional exit, which is why the task body is not wrapped in a try/finally here.
     */
    static Runnable wrap(Map<String, String> labels, Runnable body) {
        LabelsSet set = new LabelsSet(labels);
        return () -> Pyroscope.LabelsWrapper.run(set, body);
    }
}
