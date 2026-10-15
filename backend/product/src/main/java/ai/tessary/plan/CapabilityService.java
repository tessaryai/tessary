// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.featureflags.FeatureFlags;
import ai.tessary.featureflags.FlagContext;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The capability gate: the single seam every gated capability funnels through, the way {@code
 * TenantPathResolver} is the single RBAC gate.
 *
 * <h2>How a capability resolves</h2>
 * Two layers, most specific wins:
 *
 * <ol>
 *   <li>the org's own <b>override</b>, if it states one ({@link FeatureFlags});
 *   <li>this build's <b>default</b>, from {@link CapabilityDefaults}.
 * </ol>
 *
 * There is no third layer here: plan tiers and numeric quotas are not this class's concern, and it
 * never reads a plan.
 *
 * <h2>What an empty flag store does</h2>
 * {@link FeatureFlags#override} returns empty when nothing holds an opinion (no row, no matching
 * rule, an uninitialized client), so the answer falls through to the default. A fresh install, a
 * wiped table, and an unreachable flag service all serve the same coherent product.
 *
 * <h2>Off the hot path</h2>
 * A request/run that gates many items should call {@link #resolve} once and ask the returned
 * {@link CapabilitySet} per item, never re-hitting {@link FeatureFlags}.
 */
@Service
public class CapabilityService {

    private final FeatureFlags featureFlags;
    private final CapabilityDefaults defaults;

    public CapabilityService(FeatureFlags featureFlags, CapabilityDefaults defaults) {
        this.featureFlags = featureFlags;
        this.defaults = defaults;
    }

    public boolean isEnabled(String orgId, Capability capability) {
        return resolveOne(FlagContext.forOrg(orgId), capability);
    }

    /** Every capability resolved in one pass. */
    public CapabilitySet resolve(String orgId) {
        FlagContext ctx = FlagContext.forOrg(orgId);
        Map<Capability, Boolean> resolved = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            resolved.put(capability, resolveOne(ctx, capability));
        }
        return new CapabilitySet(Map.copyOf(resolved));
    }

    /** The server-side gate; throws {@link CapabilityError#DISABLED} (403). */
    public void require(String orgId, Capability capability) {
        if (!isEnabled(orgId, capability)) {
            throw new TessaryException(CapabilityError.DISABLED, capability.wire());
        }
    }

    /** Public so the Features screen can tell "on by default" from "turned on", without a second copy of the policy. */
    public boolean defaultFor(Capability capability) {
        return defaults.defaultFor(capability);
    }

    private boolean resolveOne(FlagContext ctx, Capability capability) {
        return featureFlags.override(capability.wire(), ctx).orElseGet(() -> defaultFor(capability));
    }

    /** Fully resolved, so a check is a map lookup with no I/O. */
    public record CapabilitySet(Map<Capability, Boolean> capabilities) {

        /**
         * A missing key is off: {@link #resolve} fills every constant, so absence means a hand-built, incomplete set.
         */
        public boolean isEnabled(Capability capability) {
            return capabilities.getOrDefault(capability, false);
        }
    }
}
