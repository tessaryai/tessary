// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.endusers.facts.FoldService;
import org.springframework.stereotype.Component;

/** Stage 1: the fold replaces each closed day it owes up to yesterday, so it does not read the job's day. */
@Component
public class FoldStage implements EndUsersStage {

    private final FoldService fold;

    public FoldStage(FoldService fold) {
        this.fold = fold;
    }

    @Override
    public String name() {
        return "fold";
    }

    @Override
    public long run(String projectId, int day) {
        return fold.fold(projectId).idDays();
    }
}
