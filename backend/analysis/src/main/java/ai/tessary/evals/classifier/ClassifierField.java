// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import ai.tessary.evals.classifier.detector.EncoderDetector;
import ai.tessary.evals.classifier.detector.RegexDetector;
import ai.tessary.evals.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.evals.classifier.substrate.SubstrateObservation;

/**
 * Which side of the conversation a text-scoring detector focuses on: the user-side {@code input}, the
 * agent-side {@code output}, or both.
 *
 * <p>For the <b>encoder classifier</b> ({@link EncoderDetector}) this selects the trailing scored
 * message of the assembled conversation thread ({@link ConversationThreadAssembler}) — by DEFAULT the
 * full user↔assistant thread (system excluded), so a head sees the whole interaction it is meant to
 * judge (the frustration in "nevermind" is only legible against the assistant turns it reacts to).
 * {@code INPUT} ends the thread at the scored user turn; {@code OUTPUT}/{@code BOTH} carry that user
 * turn as context and end at the scored assistant turn.
 *
 * <p>How MUCH of that thread a head receives is the head's own choice, not this enum's: a pooled
 * single-utterance encoder is diluted by context it cannot weight, so it may narrow via {@link
 * ConversationThreadAssembler.ContextPolicy}. The field selects the SIDE; the policy selects the DEPTH.
 *
 * <p>For the literal <b>regex</b> tier ({@link RegexDetector} — user keyword signals, not a semantic
 * classifier) this stays a per-observation, field-restricted selection: "OUTPUT contains X" must mean
 * the output alone, so it reads {@link SubstrateObservation#inputText()}/{@link
 * SubstrateObservation#outputText()} directly rather than the cross-turn thread.
 */
public enum ClassifierField {
    INPUT,
    OUTPUT,
    BOTH
}
