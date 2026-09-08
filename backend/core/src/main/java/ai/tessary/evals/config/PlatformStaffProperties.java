// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The platform-staff allowlist, bound from {@code evals.platform.staff-emails} — the identities that may
 * administer another org's plan by hand, which is how a pilot customer gets a paid tier — the only way,
 * since there is no self-serve purchase path.
 *
 * <p><b>Empty means nobody.</b> Unlike a capability default, an unset {@code EVALS_PLATFORM_STAFF_EMAILS}
 * grants no one anything — a deployment that forgets the variable loses the pilot-upgrade affordance rather
 * than opening it. Staff identity is only half the gate; {@code PlatformStaff} additionally requires
 * owner/admin standing in the target org.
 *
 * <p>The entries are matched against the email on the principal row, which WorkOS verified at sign-up and
 * which no request header can influence. Comparison is lowercase so a differently-cased sign-in still matches.
 */
@Component
@ConfigurationProperties(prefix = "evals.platform")
public class PlatformStaffProperties {

    private Set<String> staffEmails = Set.of();

    /** Whether {@code email} is on the allowlist. False for null/blank, and false when the list is empty. */
    public boolean isStaffEmail(@Nullable String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return staffEmails.contains(normalize(email));
    }

    public Set<String> getStaffEmails() {
        return staffEmails;
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
