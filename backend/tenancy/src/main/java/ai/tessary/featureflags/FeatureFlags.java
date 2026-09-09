// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.util.Optional;

/**
 * The feature-flag SPI: whether an adapter has a value for one flag key, for one org, or the
 * absence of one. This seam holds no defaults: there is no {@code isOn(key)} and no
 * {@code isOn(key, default)}, so an empty, unreachable, or misconfigured flag store cannot
 * manufacture a {@code true} or a {@code false} of its own.
 *
 * <p>{@link DbFeatureFlags} is this build's adapter: per-org rows in {@code org_feature_flag},
 * written through the capability-override endpoints. A build may swap in another adapter behind
 * the same interface.
 *
 * <p>Every default lives one layer up, in {@code ai.tessary.plan.CapabilityService}. That is what
 * makes an empty flag store serve a coherent product rather than "everything on" or "everything
 * off".
 *
 * <p>The sole consumer is {@code ai.tessary.plan.CapabilityService}.
 */
public interface FeatureFlags {

    /**
     * The adapter's value for {@code key} in {@code context}, or empty when it has none: no row, no matching
     * rule, an uninitialized client, or any evaluation error. Empty means "nobody has an opinion", never "off".
     */
    Optional<Boolean> override(String key, FlagContext context);
}
