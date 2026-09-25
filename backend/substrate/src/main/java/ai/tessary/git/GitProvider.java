// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.util.Locale;

/**
 * Git hosting providers a project can bind to. GitHub is the first
 * implementation; GitLab / Bitbucket / self-hosted slot in behind the same
 * {@link GitProviderClient} / {@link GitTokenService} SPI.
 */
public enum GitProvider {
    GITHUB;

    public static GitProvider fromWire(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "github" -> GITHUB;
            default -> throw new IllegalArgumentException("unsupported git provider: " + s);
        };
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
