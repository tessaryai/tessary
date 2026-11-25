// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The hits of each issue (spec 8.1, 8.2), by lineage key. */
public record HitSet(Map<String, LineageHits> byLineage) {

    /**
     * @param hitDays key index to its ascending hit days
     * @param reachRoutes the routes that hold 90% of the evidence, most first
     * @param complete every finding's detector records every hit
     * @param alwaysPresent the first evidence is within 14 days of the first traffic on the reach or of {@code t0}
     */
    public record LineageHits(
            Map<Integer, List<Integer>> hitDays,
            @Nullable Integer firstEvidenceDay,
            @Nullable Integer lastEvidenceDay,
            List<Integer> reachRoutes,
            boolean complete,
            boolean alwaysPresent) {}
}
