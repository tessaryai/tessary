// SPDX-License-Identifier: Apache-2.0
/**
 * The flag-override seam under the capability layer. It answers one question: does anything hold a
 * value for this key, for this org, and holds no defaults of its own.
 *
 * <h2>Where the defaults live</h2>
 * One layer up, in {@code ai.tessary.plan.CapabilityService}: this build serves every capability on
 * except {@code behavior_drift} and {@code sop_conformance}, whose classifier code this build does
 * not carry, and {@code triage_automatic}, which spends model budget without a ceiling and is
 * therefore an opt-in. {@link ai.tessary.featureflags.FeatureFlags} deliberately has no {@code isOn}
 * and no {@code isOn(key, default)}, so an empty or unreachable flag store cannot manufacture a
 * value; it can only decline to have an opinion, which lands every org on that default.
 *
 * <h2>The adapter here</h2>
 * {@link ai.tessary.featureflags.DbFeatureFlags} stores per-org rows in {@code org_feature_flag},
 * written through the capability-override endpoints and cached per org for ten seconds. A build may
 * register a different {@link ai.tessary.featureflags.FeatureFlags} implementation in its place.
 *
 * <h2>Targeting</h2>
 * Global or one org, and nothing finer. {@link ai.tessary.featureflags.FlagContext} carries an org
 * ULID or nothing. There is no user or project scope by design: a capability is something an
 * organization has, not something two members of the same org can disagree about. A global
 * evaluation has no override to find here, since this adapter stores nothing global, and it lands
 * on the default.
 */
@NullMarked
package ai.tessary.featureflags;

import org.jspecify.annotations.NullMarked;
