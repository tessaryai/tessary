// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.util.Optional;

/**
 * The feature-flag SPI. It holds no defaults, so an empty, unreachable or misconfigured store cannot manufacture a
 * {@code true} or {@code false}. {@link DbFeatureFlags} is this build's adapter. Every default lives in
 * {@code ai.tessary.plan.CapabilityService}, its sole consumer.
 */
public interface FeatureFlags {

    /**
     * Empty when the adapter has no row, no matching rule, an uninitialized client or an evaluation error. Empty means
     * "nobody has an opinion", never "off".
     */
    Optional<Boolean> override(String key, FlagContext context);
}
