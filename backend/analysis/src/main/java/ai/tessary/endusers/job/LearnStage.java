// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.endusers.facts.RouteLearner;
import org.springframework.stereotype.Component;

/** Stage 3: learns the route classes from the live traces of the 28 days that end on the job's day. */
@Component
public class LearnStage implements EndUsersStage {

    private final RouteLearner learner;

    public LearnStage(RouteLearner learner) {
        this.learner = learner;
    }

    @Override
    public String name() {
        return "learn";
    }

    @Override
    public long run(String projectId, int day) {
        return learner.learn(projectId, day).byRoute().size();
    }
}
