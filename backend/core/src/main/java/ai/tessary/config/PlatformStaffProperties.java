// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The platform-staff allowlist ({@code tessary.platform.staff-emails}) for non-public {@code /actuator/**}. Empty means
 * nobody, so a forgotten variable keeps those paths closed. Matched case-insensitively against the WorkOS-verified
 * principal email, which no request header can influence.
 */
@Component
@ConfigurationProperties(prefix = "tessary.platform")
public class PlatformStaffProperties {

    private Set<String> staffEmails = Set.of();

    /** False for null or blank, and when the list is empty. */
    public boolean isStaffEmail(@Nullable String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return staffEmails.contains(normalize(email));
    }

    public void setStaffEmails(@Nullable List<String> emails) {
        Set<String> normalized = new LinkedHashSet<>();
        if (emails != null) {
            for (String e : emails) {
                if (e != null && !e.isBlank()) {
                    normalized.add(normalize(e));
                }
            }
        }
        this.staffEmails = Set.copyOf(normalized);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
