// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.plan;

import ai.tessary.evals.edition.Edition;
import ai.tessary.evals.featureflags.FeatureFlags;
import ai.tessary.evals.featureflags.FlagContext;
import ai.tessary.evals.open.errors.CapabilityError;
import ai.tessary.evals.open.errors.EvalsException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The capability gate — the single seam every gated capability funnels through, the way {@code
 * TenantPathResolver} is the single RBAC gate.
 *
 * <h2>How a capability resolves</h2>
 * Two layers, most specific wins:
 *
 * <ol>
 *   <li>the org's own <b>override</b>, if it states one ({@link FeatureFlags});
 *   <li>the <b>edition default</b> below: on, except {@link #OFF_BY_DEFAULT} and, in the open edition
 *       only, {@link #UNAVAILABLE_IN_OPEN_EDITION}.
 * </ol>
 *
 * There is no third layer here. Plan tiers and numeric quotas are the HOSTED product's concern and live in
 * the paid overlay's entitlement engine; the open edition is uncapped and this
 * class never reads a plan. {@link Capability#defaultEnabled()} is likewise not read here: those flags encode
 * Tessary's hosted FREE tier, where 11 of 17 capabilities are off, and serving that to a self-hoster would ship
 * most of the product dark for no reason a self-hoster has.
 *
 * <h2>What an empty flag store does</h2>
 * {@link FeatureFlags#override} returns empty when nothing holds an opinion — no row, no matching rule, an
 * uninitialized client — so the answer falls through to the open-edition default. A fresh install, a wiped
 * table and an unreachable hosted flag service all serve the same coherent product.
 *
 * <h2>Off the hot path</h2>
 * A request/run that gates many items should call {@link #resolve} ONCE and ask the returned
 * {@link CapabilitySet} per item, never re-hitting {@link FeatureFlags}.
 */
@Service
public class CapabilityService {

    /**
     * Capabilities an OPEN build cannot honour, because the classifier code behind them is not in it —
     * {@code behavior_drift} and {@code sop_conformance} are genuine extractions (epic 1 issues 4 and 5 move
     * their source out), and {@code frustration} and {@code groundedness} joined them (epic 1 issues 19 and
     * 20, #887/#888) — four paid classifiers, not two. They are not all the same shape: groundedness is a
     * real extraction like behaviour drift and conformance, but frustration has no dedicated detector class
     * at all — it rides the generic, open {@code EncoderDetector} — so what is paid there is the trained
     * artifact and its registration, not a package. All four are reported separately from "off" by {@link
     * #unavailable} so a client can say "not in this edition" rather than offering a switch that would do
     * nothing, and the write path refuses to set an override for one.
     *
     * <p>THIS SET HAS TO BECOME EDITION-AWARE WHEN THE PAID CLASSIFIERS BECOME LOADABLE — epic 1 issues 4, 5,
     * 19 and 20 (#840, #841, #887, #888), plus epic 5's edition signal. NOT #876, which this note used to
     * name: #876 built the classifier registry and #881 put the paid PLAN module on a runtime classpath, and
     * neither moves a classifier. Every one of the four is still reachable from {@code backend/analysis} one
     * way or another — {@code behavior_drift}/{@code sop_conformance} as {@code @Component}s, {@code
     * frustration} as the always-open {@code EncoderDetector}, {@code groundedness} as a {@code
     * DetectorSupplier}-discovered bean once {@code tessary-paid/groundedness} is on the classpath — so
     * deriving this set from what is registered would report some or all of them AVAILABLE in the open build
     * and break epic 1's own gate, which requires exactly these four to read unavailable. So the set stays a
     * per-edition CONSTANT and the {@link Edition} bean decides whether it applies: open reads all four
     * unavailable, paid reads none (epic 5, #1133). The edition is derived from the classpath, never from a
     * property, for the reason {@link Edition}'s javadoc gives.
     */
    private static final Set<Capability> UNAVAILABLE_IN_OPEN_EDITION = EnumSet.of(
            Capability.BEHAVIOR_DRIFT, Capability.SOP_CONFORMANCE, Capability.FRUSTRATION, Capability.GROUNDEDNESS);

    /**
     * Capabilities that are present but start off. Exactly one: automatic Layer-2 triage drives LLM
     * escalation with no ceiling (decision D6), so running it unattended is an opt-in an operator takes
     * knowingly. It is toggleable per org like anything else — this is a default, not a restriction.
     */
    private static final Set<Capability> OFF_BY_DEFAULT = EnumSet.of(Capability.TRIAGE_AUTOMATIC);

    private final FeatureFlags featureFlags;
    private final Edition edition;

    public CapabilityService(FeatureFlags featureFlags, Edition edition) {
        this.featureFlags = featureFlags;
        this.edition = edition;
    }

    // ---- capability resolution --------------------------------------------------------------

    /** Whether {@code capability} is on for {@code orgId}: the org's override, else the open-edition default. */
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
     *
     * <p>This call site is why epic 1 issue 9 (#845) RENAMED the enum rather than deleting it. It used to
     * be {@code PlanError.CAPABILITY_DISABLED}, and "plan" is paid vocabulary {@code shared} must not
     * carry — but the open capability gate needs a 403 code, so the type stayed and the name changed.
     * The wire code moved with it: {@code PLAN.CAPABILITY_DISABLED} is now {@code CAPABILITY.DISABLED}.
     */
    public void require(String orgId, Capability capability) {
        if (!isEnabled(orgId, capability)) {
            throw new EvalsException(CapabilityError.DISABLED, capability.wire());
        }
    }

    /**
     * The capabilities this edition cannot honour whatever an org asks for — the "not in this edition" half of
     * the capability payload, and the set the override write path refuses.
     */
    public Set<Capability> unavailable() {
        return edition.paid() ? Set.of() : UNAVAILABLE_IN_OPEN_EDITION;
    }

    /**
     * This edition's answer with nobody's override in play: on, except {@link #OFF_BY_DEFAULT} and, in the
     * open edition, {@link #UNAVAILABLE_IN_OPEN_EDITION}. Public because the Features settings read renders
     * "on, but that is only the default" differently from "on, because somebody turned it on", and recomputing
     * this table in the controller would be two copies of one policy.
     */
    public boolean defaultFor(Capability capability) {
        return !unavailable().contains(capability) && !OFF_BY_DEFAULT.contains(capability);
    }

    /**
     * The org's override of the open-edition default.
     *
     * <p>NOTE THAT AN OVERRIDE STILL WINS FOR AN UNAVAILABLE CAPABILITY, and that is deliberate rather than an
     * oversight. Unavailability is enforced where overrides are WRITTEN (the override endpoint 422s on
     * {@code behavior_drift} / {@code sop_conformance}) and reported where the payload is READ; hard-falsing it
     * here instead would refuse the write path its own 422 already covers, twice, in two places that would
     * then have to agree. The two Testcontainers tests that used to drive this seam on and off
     * ({@code SopIntakeServiceTest}, {@code SopCompileWorkerIntegrationTest}) are gone — deleted with the
     * paid code they covered, #882 carries the debt — so the coverage argument this note used to rest on
     * has expired; the write-side/read-side split is the reason that remains.
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
