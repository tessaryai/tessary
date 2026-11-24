// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import java.util.Optional;

/**
 * The hit rule of the detectors one build ships. {@link OpenHitSource} covers the open detectors; another build adds a
 * bean for its own kinds. A finding no source covers gets {@link HitRule#DEFAULT}, which is not complete.
 */
public interface EndUserHitSource {

    Optional<HitRule> ruleFor(HitFinding finding);
}
