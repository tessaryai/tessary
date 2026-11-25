// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.endusers.hits.HitSweepService;
import org.springframework.stereotype.Component;

/** Stage 2: rebuilds the hits of open findings and of findings with evidence in the last 7 days. */
@Component
public class ReconcileStage implements EndUsersStage {

    private final HitSweepService hits;

    public ReconcileStage(HitSweepService hits) {
        this.hits = hits;
    }

    @Override
    public String name() {
        return "reconcile";
    }

    @Override
    public long run(String projectId, int day) {
        return hits.reconcile(projectId).rows();
    }
}
