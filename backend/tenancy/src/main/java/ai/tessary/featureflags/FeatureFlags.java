// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.util.Optional;

/**
 * The feature-flag SPI: whether some ADAPTER has a value for one flag key, for one org, or the absence of
 * one. CRITICAL invariant: this seam holds NO defaults. There is no {@code isOn(key)} and no
 * {@code isOn(key, default)} — the only value it can return is one an adapter actually holds, so an empty,
 * unreachable or misconfigured flag store cannot manufacture a {@code true} or a {@code false} of its own.
 *
 * <p>Two adapters implement it, and the layer above cannot tell them apart:
 *
 * <ul>
 *   <li>{@link DbFeatureFlags} — the OPEN edition. Per-org rows in {@code org_feature_flag}, written through
 *       the capability-override endpoints. This is what a self-hosted install runs on.
 *   <li>The hosted LaunchDarkly adapter, which lives in the paid overlay. Same interface, targeting rules in
 *       the LaunchDarkly console instead of rows in the operator's own database.
 * </ul>
 *
 * <p>Every default lives one layer up, in {@code ai.tessary.plan.CapabilityService} (the open-edition
 * default: on except the paid classifiers and {@code TRIAGE_AUTOMATIC}) and, on the hosted side, in a plan
 * tier's override of it. That is what makes an empty flag store serve a coherent product rather than
 * "everything on" or "everything off".
 *
 * <p>The sole consumer is {@code ai.tessary.plan.CapabilityService}.
 */
public interface FeatureFlags {

    /**
     * The adapter's value for {@code key} in {@code context}, or empty when it has none — no row, no matching
     * rule, an uninitialized client, or any evaluation error. Empty means "nobody has an opinion", never "off".
     */
    Optional<Boolean> override(String key, FlagContext context);
}
