// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.util.Locale;

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
