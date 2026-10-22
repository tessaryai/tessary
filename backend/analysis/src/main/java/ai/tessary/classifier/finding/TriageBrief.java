// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.agentrun.StepWords;
import java.util.Map;

/**
 * A {@link TriageSource}'s input to {@code BehaviorTriageEngine.rule}. Sources return a brief, not a ruling, so retry
 * backoff, the launcher circuit breaker and dead-lettering stay shared in the worker. @param dossier files written
 * under {@code dossier/}, by name @param words what the run's steps call the finding's evidence and how much of it
 * there is
 */
public record TriageBrief(Map<String, String> dossier, String prompt, StepWords.Context words) {

    public TriageBrief(Map<String, String> dossier, String prompt) {
        this(dossier, prompt, StepWords.Context.NONE);
    }
}
