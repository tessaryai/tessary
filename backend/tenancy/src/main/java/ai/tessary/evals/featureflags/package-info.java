// SPDX-License-Identifier: Apache-2.0
/**
 * The flag-override seam under the capability layer. It answers one question — does anything hold a value
 * for this key, for this org — and holds no defaults of its own.
 *
 * <h2>Where the defaults live</h2>
 * One layer up, in {@code ai.tessary.evals.plan.CapabilityService}: the open edition serves every capability
 * ON except the two whose classifier code an open build does not carry ({@code behavior_drift},
 * {@code sop_conformance}) and {@code triage_automatic}, which spends model budget without a ceiling and is
 * therefore an opt-in. {@link ai.tessary.evals.featureflags.FeatureFlags} deliberately has no {@code isOn} and
 * no {@code isOn(key, default)}, so an empty or unreachable flag store cannot manufacture a value — it can only
 * decline to have an opinion, which lands every org on that default.
 *
 * <h2>Two adapters, one interface</h2>
 * {@link ai.tessary.evals.featureflags.DbFeatureFlags} is the OPEN adapter: per-org rows in
 * {@code org_feature_flag}, written through the capability-override endpoints and cached per org for ten
 * seconds. The hosted adapter is LaunchDarkly, and it lives in the paid overlay ({@code tessary-paid/plan})
 * along with the plan tiers — a self-hosted build carries neither the SDK nor a line of config naming it.
 *
 * <h2>Targeting</h2>
 * Global or one org, and nothing finer. {@link ai.tessary.evals.featureflags.FlagContext} carries an org ULID
 * or nothing. There is no user or project scope by design: a capability is something an organization has, not
 * something two members of the same org can disagree about. A global evaluation has no override to find on
 * either adapter — the open one stores nothing global, and it lands on the open-edition default.
 */
@NullMarked
package ai.tessary.evals.featureflags;

import org.jspecify.annotations.NullMarked;
