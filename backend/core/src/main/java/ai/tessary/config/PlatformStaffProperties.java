// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The platform-staff allowlist, bound from {@code tessary.platform.staff-emails}: the identities
 * {@code PlatformStaff} lets through to the non-public {@code /actuator/**} paths.
 *
 * <p><b>Empty means nobody.</b> Unlike a capability default, an unset {@code
 * TESSARY_PLATFORM_STAFF_EMAILS} grants no one anything: a deployment that forgets the variable
 * keeps those paths closed rather than opening them.
 *
 * <p>The entries are matched against the email on the principal row, which WorkOS verified at
 * sign-up and which no request header can influence. Comparison is lowercase so a
 * differently-cased sign-in still matches.
 */
@ConfigurationProperties(prefix = "tessary.platform")
public record PlatformStaffProperties(@DefaultValue Set<String> staffEmails) {

    /** Whether {@code email} is on the allowlist. False for null/blank, and false when the list is empty. */
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
