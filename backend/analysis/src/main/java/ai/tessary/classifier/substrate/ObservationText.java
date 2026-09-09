// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

/**
 * The text a classifier embeds / labels for one observation: input + output joined, NEVER
 * truncated (per the platform rule — bound a labeling/embedding workload by example <em>count</em>, not
 * by clipping content).
 */
public final class ObservationText {

    private ObservationText() {}

    /** The full input+output text of an observation, blank when both are empty. */
    public static String of(SubstrateObservation obs) {
        String rawIn = obs.input();
        String rawOut = obs.output();
        String in = rawIn == null ? "" : rawIn;
        String out = rawOut == null ? "" : rawOut;
        if (in.isEmpty()) return out;
        if (out.isEmpty()) return in;
        return in + "\n" + out;
    }
}
