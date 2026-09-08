// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.edition;

/**
 * Which edition this JVM is running: the open build, or the open build with the paid overlay layered on
 * top (D4). The answer is derived from the CLASSPATH and nothing else: the open default below is a
 * {@code @ConditionalOnMissingBean} bean in {@code EditionConfig}, and the paid overlay displaces it with
 * a component its own auto-configuration scans in, the same displacement shape {@code OrgCreationLimit}
 * and {@code FeatureFlags} already use. There is deliberately no property or environment variable
 * behind it: an open image with {@code EVALS_EDITION=paid} set would report four capabilities available
 * with no code behind them, which is the exact lie {@code CapabilityController#requireAvailable} exists
 * to refuse.
 *
 * <p>The two wire values are the D6 telemetry contract's {@code edition} enum.
 */
public interface Edition {

    String OPEN_WIRE = "open";
    String PAID_WIRE = "paid";

    /** The edition's wire name: {@link #OPEN_WIRE} or {@link #PAID_WIRE}. */
    String wire();

    default boolean paid() {
        return PAID_WIRE.equals(wire());
    }

    /** The open edition, for the open default bean and for tests that need no Spring context. */
    static Edition open() {
        return () -> OPEN_WIRE;
    }
}
