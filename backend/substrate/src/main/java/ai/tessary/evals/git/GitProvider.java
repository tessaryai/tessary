// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

import java.util.Locale;

/**
 * Git hosting providers the observer can bind to. GitHub is the first
 * implementation; GitLab / Bitbucket / self-hosted slot in behind the same
 * {@link GitProviderClient} / {@link GitWebhookAdapter} / {@link GitTokenService}
 * SPI without touching the observer pipeline.
 */
public enum GitProvider {
    GITHUB;

    public static GitProvider fromWire(String s) {
        if (s == null) throw new IllegalArgumentException("git provider is required");
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "github" -> GITHUB;
            default -> throw new IllegalArgumentException("unsupported git provider: " + s);
        };
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
