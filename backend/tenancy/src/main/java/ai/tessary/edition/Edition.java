// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

/**
 * Which edition this JVM is running, resolved from the classpath alone: the default below is a
 * {@code @ConditionalOnMissingBean} bean in {@code EditionConfig}, and a build may override it
 * with its own component. There is deliberately no property or environment variable behind it:
 * reporting capabilities with no code behind them is the exact lie
 * {@code CapabilityController#requireAvailable} exists to refuse.
 */
public interface Edition {

    String OPEN_WIRE = "open";
    String PAID_WIRE = "paid";

    /** The edition's wire name: {@link #OPEN_WIRE} or {@link #PAID_WIRE}. */
    String wire();

    default boolean paid() {
        return PAID_WIRE.equals(wire());
    }

    /** The {@link #OPEN_WIRE} instance, for the default bean and for tests that need no Spring context. */
    static Edition open() {
        return () -> OPEN_WIRE;
    }
}
