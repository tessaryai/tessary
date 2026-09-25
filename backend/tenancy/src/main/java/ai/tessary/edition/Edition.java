// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

/**
 * Which edition this JVM is running: the bean in {@code EditionConfig}, with deliberately no
 * property or environment variable behind it, so it can only report what the code on the
 * classpath is.
 */
public interface Edition {

    String OPEN_WIRE = "open";

    /** The edition's wire name: {@link #OPEN_WIRE}. */
    String wire();

    /** The {@link #OPEN_WIRE} instance, for the default bean and for tests that need no Spring context. */
    static Edition open() {
        return () -> OPEN_WIRE;
    }
}
