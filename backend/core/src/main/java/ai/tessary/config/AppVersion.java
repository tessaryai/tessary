// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.regex.Pattern;

/**
 * The running app's version, and the git ref of the source it was released from.
 *
 * <p>The version is the packaged jar's manifest {@code Implementation-Version}, set from {@code
 * ${project.version}}, which a release build stamps from {@code IMAGE_VERSION} ({@code
 * backend/Dockerfile}). It is null outside a packaged jar (an IDE run, a test), and {@code "dev"}
 * covers that case. The telemetry heartbeat reports it; the groundedness status turns it into the
 * ref its setup prompts link to.
 */
public final class AppVersion {

    /** What a release stamps: {@code 1.3.0}, never a prefix or a suffix. */
    private static final Pattern RELEASE = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private AppVersion() {}

    /** The running version, or {@code "dev"} when the jar carries none. */
    public static String current() {
        String v = AppVersion.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }

    /**
     * The git ref that holds this version's source: the release tag ({@code v1.3.0}) for a release
     * version, and {@code main} for anything else, such as {@code dev} or a short commit SHA. A link
     * to a file at this ref shows the file as the running version shipped it.
     */
    public static String sourceRef(String version) {
        return RELEASE.matcher(version).matches() ? "v" + version : "main";
    }
}
