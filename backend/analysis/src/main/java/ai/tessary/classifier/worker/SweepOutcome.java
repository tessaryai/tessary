// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

/**
 * What one {@link ClassifierSweep} pass did. Kept to the two numbers every sweep can answer, so the port does not grow
 * a field per classifier. @param scanned observations, traces or window heads, depending on the grain @param fired
 * findings written or bumped; many units can produce one finding
 */
public record SweepOutcome(int scanned, int fired) {}
