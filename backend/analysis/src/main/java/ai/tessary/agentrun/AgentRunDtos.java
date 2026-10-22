// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The steps of one triage or RCA run on the wire, shared by the finding and the RCA steps reads. */
public final class AgentRunDtos {

    private AgentRunDtos() {}

    /** @param stepCount every step the run took, including steps past the stored ones */
    public record RunStepsView(
            @JsonProperty("run_id") String runId,
            @JsonProperty("step_count") int stepCount,
            List<StepView> steps) {

        public static RunStepsView of(AgentRunRepository.RunSteps run) {
            return new RunStepsView(
                    run.runId(),
                    run.stepCount(),
                    run.steps().stream().map(StepView::of).toList());
        }
    }

    /** @param endedMs null while the step runs @param ok null while the step runs */
    public record StepView(
            int seq,
            String words,
            String doing,
            @JsonProperty("started_ms") long startedMs,
            @JsonProperty("ended_ms") @Nullable Long endedMs,
            @Nullable Boolean ok) {

        static StepView of(AgentRunRepository.StepRow step) {
            return new StepView(step.seq(), step.words(), step.doing(), step.startedMs(), step.endedMs(), step.ok());
        }
    }
}
