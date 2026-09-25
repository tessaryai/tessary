// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.featureflags.FeatureFlags;
import ai.tessary.featureflags.FlagContext;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
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
 *   <li>this build's <b>default</b> below: on, except {@link #OFF_BY_DEFAULT}.
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

    /**
     * Capabilities that are present but start off. Exactly one: automatic Layer-2 triage drives LLM
     * escalation with no ceiling, so running it unattended is an opt-in an operator takes knowingly.
     * It is toggleable per org like anything else; this is a default, not a restriction.
     */
    private static final Set<Capability> OFF_BY_DEFAULT = EnumSet.of(Capability.TRIAGE_AUTOMATIC);

    private final FeatureFlags featureFlags;

    public CapabilityService(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    // ---- capability resolution --------------------------------------------------------------

    /** Whether {@code capability} is on for {@code orgId}: the org's override, else the default. */
    public boolean isEnabled(String orgId, Capability capability) {
        return resolveOne(FlagContext.forOrg(orgId), capability);
    }

    /**
     * Every capability's resolved state for an org, in ONE pass — the object a session is served and the object
     * a run gates many items against.
     */
    public CapabilitySet resolve(String orgId) {
        FlagContext ctx = FlagContext.forOrg(orgId);
        Map<Capability, Boolean> resolved = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            resolved.put(capability, resolveOne(ctx, capability));
        }
        return new CapabilitySet(Map.copyOf(resolved));
    }

    /**
     * Throw {@link CapabilityError#DISABLED} (403) unless {@link #isEnabled}. The server-side gate a
     * controller calls before a gated capability, mirroring {@code Resolved#require} for RBAC.
     */
    public void require(String orgId, Capability capability) {
        if (!isEnabled(orgId, capability)) {
            throw new TessaryException(CapabilityError.DISABLED, capability.wire());
        }
    }

    /**
     * This build's answer with nobody's override in play: on, except {@link #OFF_BY_DEFAULT}. Public
     * because the Features settings read renders "on, but that is only the default" differently from
     * "on, because somebody turned it on", and recomputing this table in the controller would be two
     * copies of one policy.
     */
    public boolean defaultFor(Capability capability) {
        return !OFF_BY_DEFAULT.contains(capability);
    }

    /** The org's override of the default. */
    private boolean resolveOne(FlagContext ctx, Capability capability) {
        return featureFlags.override(capability.wire(), ctx).orElseGet(() -> defaultFor(capability));
    }

    /**
     * An org's resolved capabilities — the payload a session is served and the object a run gates against.
     * Every entry is already resolved, so a check is a map lookup with no I/O.
     */
    public record CapabilitySet(Map<Capability, Boolean> capabilities) {

        /**
         * Whether {@code capability} is on. A capability missing from the map is off: {@link #resolve} always
         * fills every constant, so an absent key means the set was built by hand and is incomplete.
         */
        public boolean isEnabled(Capability capability) {
            return capabilities.getOrDefault(capability, false);
        }
    }
}
