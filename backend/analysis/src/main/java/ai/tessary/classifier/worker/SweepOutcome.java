// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

/**
 * What one {@link ClassifierSweep} pass did, in the two numbers every sweep can answer and the
 * worker's completion line prints.
 *
 * <p>Two fields, not three. {@code MetricDriftSweep} additionally counts the windows it rotated,
 * which is the number that actually describes progress THERE and means nothing anywhere else — so it
 * keeps that count on its own richer return type and narrows to this one at the seam. A port whose
 * shape is the union of what its implementations happen to want is a port that grows a field per
 * classifier.
 *
 * @param scanned units read this pass — observations, traces or window heads depending on the grain.
 *     What "a unit" means is the sweep's business; the worker only logs it.
 * @param fired findings written or bumped this pass. Never one per scanned unit: the fitting tier's
 *     whole point is that many units produce one finding.
 */
public record SweepOutcome(int scanned, int fired) {

    /** A pass that read nothing and wrote nothing — a disabled config, an empty page, a missing bundle. */
    public static final SweepOutcome EMPTY = new SweepOutcome(0, 0);
}
