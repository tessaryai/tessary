// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import ai.tessary.config.AppVersion;

/** Which edition this JVM runs. Deliberately no property behind it, so it can only report the code on the classpath. */
public interface Edition {

    String OPEN_WIRE = "open";

    String wire();

    /** The build Settings shows. An edition layered on an open release may name its own build. */
    default String version() {
        return AppVersion.current();
    }

    /** For the default bean and for tests without a Spring context. */
    static Edition open() {
        return () -> OPEN_WIRE;
    }
}
