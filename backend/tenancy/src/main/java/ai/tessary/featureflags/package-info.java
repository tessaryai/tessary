// SPDX-License-Identifier: Apache-2.0
/**
 * The flag-override seam under the capability layer: does anything hold a value for this key and org. Holds no
 * defaults; those live in {@code ai.tessary.plan.CapabilityService}, and {@link ai.tessary.featureflags.FeatureFlags}
 * has no {@code isOn(key, default)}, so an empty or unreachable store can only decline to have an opinion. Targeting is
 * global or one org only: a capability is something an organization has.
 */
@NullMarked
package ai.tessary.featureflags;

import org.jspecify.annotations.NullMarked;
