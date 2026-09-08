// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything a {@link TriageSource} has to say about one job, in the exact three arguments
 * {@code BehaviorTriageEngine.rule} takes.
 *
 * <p><b>Why the brief and not the ruling.</b> A source could have run the engine itself and returned a
 * verdict; it does not, because everything AROUND the run is shared and must stay shared — the retry
 * backoff, the launcher circuit breaker, the {@code TRIAGE_LAUNCHER_UNAVAILABLE} release-without-attempt
 * path, and the ops log. Duplicating that per store is how two stores start dead-lettering differently.
 * So the source renders the dossier and the source records the ruling; the worker owns the run.
 *
 * @param dossier the files written under {@code dossier/} in the sandbox, by name
 * @param prompt the ruling task, composed for this claim
 * @param claimJson the detector's own numbers, held verbatim so the arithmetic abort check compares the
 *     agent's recomputation against the same string the agent read. Null when the claim has no
 *     machine-readable form — the prose file still carries the numbers, so the run proceeds unchecked
 *     rather than failing.
 */
public record TriageBrief(
        Map<String, String> dossier,
        String prompt,
        @Nullable String claimJson) {}
