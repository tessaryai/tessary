// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.edition.Edition;
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
 *   <li>this build's <b>default</b> below: on, except {@link #OFF_BY_DEFAULT} and, where it
 *       applies, {@link #UNAVAILABLE_IN_OPEN_EDITION}.
 * </ol>
 *
 * There is no third layer here: plan tiers and numeric quotas are not this class's concern, and it
 * never reads a plan. {@link Capability#defaultEnabled()} is likewise not read here: those flags
 * encode Tessary's hosted free tier, where most capabilities are off, and serving that to a
 * self-hoster would ship most of the product dark for no reason a self-hoster has.
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
     * Capabilities this build cannot honour, because the classifier code behind them is not on this
     * classpath. They are not all the same shape: some are missing a dedicated detector class
     * entirely, while {@code frustration} rides the generic, always-open {@code EncoderDetector} —
     * what is missing there is the trained artifact and its registration, not a package. All four
     * are reported separately from "off" by {@link #unavailable} so a client can say "not
     * available" rather than offering a switch that would do nothing, and the write path refuses to
     * set an override for one.
     *
     * <p>This stays a constant rather than something derived from what is registered: {@code
     * frustration}'s detector is reachable from {@code backend/analysis} regardless, so deriving
     * availability from what is registered would misreport it as available here. The {@link Edition}
     * bean decides whether this set applies at all; the edition is derived from the classpath, never
     * from a property, for the reason {@link Edition}'s javadoc gives.
     */
    private static final Set<Capability> UNAVAILABLE_IN_OPEN_EDITION = EnumSet.of(
            Capability.BEHAVIOR_DRIFT, Capability.SOP_CONFORMANCE, Capability.FRUSTRATION, Capability.GROUNDEDNESS);

    /**
     * Capabilities that are present but start off. Exactly one: automatic Layer-2 triage drives LLM
     * escalation with no ceiling, so running it unattended is an opt-in an operator takes knowingly.
     * It is toggleable per org like anything else; this is a default, not a restriction.
     */
    private static final Set<Capability> OFF_BY_DEFAULT = EnumSet.of(Capability.TRIAGE_AUTOMATIC);

    private final FeatureFlags featureFlags;
    private final Edition edition;

    public CapabilityService(FeatureFlags featureFlags, Edition edition) {
        this.featureFlags = featureFlags;
        this.edition = edition;
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
     * The capabilities this build cannot honour whatever an org asks for: the "not available" half
     * of the capability payload, and the set the override write path refuses.
     */
    public Set<Capability> unavailable() {
        return edition.paid() ? Set.of() : UNAVAILABLE_IN_OPEN_EDITION;
    }

    /**
     * This build's answer with nobody's override in play: on, except {@link #OFF_BY_DEFAULT} and,
     * where it applies, {@link #UNAVAILABLE_IN_OPEN_EDITION}. Public because the Features settings
     * read renders "on, but that is only the default" differently from "on, because somebody turned
     * it on", and recomputing this table in the controller would be two copies of one policy.
     */
    public boolean defaultFor(Capability capability) {
        return !unavailable().contains(capability) && !OFF_BY_DEFAULT.contains(capability);
    }

    /**
     * The org's override of the default.
     *
     * <p>An override still wins for an unavailable capability, and that is deliberate rather than an
     * oversight: unavailability is enforced where overrides are written (the override endpoint 422s
     * on {@code behavior_drift} / {@code sop_conformance}) and reported where the payload is read.
     * Hard-falsing it here too would refuse the write path its own 422 already covers, in two places
     * that would then have to agree.
     */
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
         * fills every constant, so an absent key means the set was built by hand and is incomplete — and
         * {@link Capability#defaultEnabled()} is the wrong fallback here, being the hosted free tier.
         */
        public boolean isEnabled(Capability capability) {
            return capabilities.getOrDefault(capability, false);
        }
    }
}
