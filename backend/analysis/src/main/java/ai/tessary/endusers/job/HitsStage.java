// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.endusers.hits.HitSweepService;
import org.springframework.stereotype.Component;

/** The one stage of the hourly job: the hit sweep from the project's watermark. */
@Component
public class HitsStage implements EndUsersStage {

    private final HitSweepService hits;

    public HitsStage(HitSweepService hits) {
        this.hits = hits;
    }

    @Override
    public String name() {
        return "hits";
    }

    @Override
    public long run(String projectId, int day) {
        return hits.sweep(projectId).rows();
    }
}
