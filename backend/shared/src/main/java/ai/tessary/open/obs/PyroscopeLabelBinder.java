// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import io.pyroscope.labels.v2.LabelsSet;
import io.pyroscope.labels.v2.Pyroscope;
import java.util.Map;

/**
 * The only class referencing Pyroscope types, kept separate from {@link ProfilingLabels} so the JVM links
 * {@code io.pyroscope} symbols only after the guard confirmed the agent is attached. Inlining them into the guard would
 * reintroduce the {@code NoClassDefFoundError} it prevents.
 */
final class PyroscopeLabelBinder {

    private PyroscopeLabelBinder() {}

    /** {@code LabelsWrapper.run} unbinds even on an exceptional exit, so no try/finally is needed here. */
    static Runnable wrap(Map<String, String> labels, Runnable body) {
        LabelsSet set = new LabelsSet(labels);
        return () -> Pyroscope.LabelsWrapper.run(set, body);
    }
}
