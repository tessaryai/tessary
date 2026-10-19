// SPDX-License-Identifier: Apache-2.0
package ai.tessary.license;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One deployment's entitlement, as the build that verified it hands it over. It names capabilities by their wire key
 * and limits by the unit they bound; a key it does not name is unconstrained. It carries no plan, price or customer
 * identity beyond a label the customer chose: a copy on a customer's box reveals nothing about anyone else.
 *
 * @param licenseId the id the issuer recorded it under
 * @param label words the customer chose to see, or null
 * @param instanceId the install the license is bound to, or null when it floats
 * @param expiresAt the last day it is in force, UTC
 * @param graceDays how many days past {@code expiresAt} the build keeps honoring it
 */
public record License(
        String licenseId,
        @Nullable String label,
        @Nullable String instanceId,
        LocalDate issuedAt,
        LocalDate expiresAt,
        int graceDays,
        Map<String, Boolean> capabilities,
        Map<String, Long> limits) {

    public License {
        capabilities = Map.copyOf(capabilities);
        limits = Map.copyOf(limits);
    }

    /** The license's word on a capability: empty when it names none, so the key is unconstrained. */
    public Optional<Boolean> allows(String wireKey) {
        return Optional.ofNullable(capabilities.get(wireKey));
    }

    /** The license's cap on a unit, empty when it names none. */
    public Optional<Long> limit(String unit) {
        return Optional.ofNullable(limits.get(unit));
    }

    /** Past its last day, UTC. Still honored until {@link #lapsed}. */
    public boolean expired(Instant now) {
        return today(now).isAfter(expiresAt);
    }

    /** Past its last day and its grace: no longer honored. */
    public boolean lapsed(Instant now) {
        return today(now).isAfter(expiresAt.plusDays(graceDays));
    }

    private static LocalDate today(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
