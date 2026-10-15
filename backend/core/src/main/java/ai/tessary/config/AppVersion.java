// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.regex.Pattern;

/**
 * The running app's version (the jar manifest's {@code Implementation-Version}, stamped from {@code IMAGE_VERSION} by a
 * release build) and its source git ref. {@code "dev"} outside a packaged jar.
 */
public final class AppVersion {

    /** {@code 1.3.0}, never a prefix or a suffix. */
    private static final Pattern RELEASE = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private AppVersion() {}

    public static String current() {
        String v = AppVersion.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }

    /**
     * {@code v1.3.0} for a release version, {@code main} for anything else, such as {@code dev} or a short commit SHA.
     */
    public static String gitRef(String version) {
        return RELEASE.matcher(version).matches() ? "v" + version : "main";
    }
}
