// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The platform-staff allowlist ({@code tessary.platform.staff-emails}) for non-public {@code /actuator/**}. Empty means
 * nobody, so a forgotten variable keeps those paths closed. Matched case-insensitively against the WorkOS-verified
 * principal email, which no request header can influence.
 */
@ConfigurationProperties(prefix = "tessary.platform")
public record PlatformStaffProperties(@DefaultValue Set<String> staffEmails) {

    /** False for null or blank, and when the list is empty. */
    public boolean isStaffEmail(@Nullable String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return staffEmails.contains(normalize(email));
    }

    public PlatformStaffProperties {
        Set<String> normalized = new LinkedHashSet<>();
        for (String e : staffEmails) {
            if (e != null && !e.isBlank()) {
                normalized.add(normalize(e));
            }
        }
        staffEmails = Set.copyOf(normalized);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
