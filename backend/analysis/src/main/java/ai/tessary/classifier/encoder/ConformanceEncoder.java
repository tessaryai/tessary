// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.encoder;

import ai.tessary.classifier.detector.EncoderScorer;
import java.util.List;

/**
 * Embeds turn text with the frozen sentence encoder the artifact bundle was fitted against.
 *
 * <p>Same port as {@link EncoderScorer}, which the built-in encoder classifiers (frustration,
 * groundedness) also serve through: the interface names the model ({@code checkpoint} here,
 * {@code head} there) and carries no opinion about who runs it. The production implementation is
 * {@link HttpConformanceEncoder}, calling classify-service's {@code POST /embed} endpoint, which
 * tokenizes to 256 with the checkpoint's own tokenizer, mean-pools with the mask, and L2-normalizes.
 * This tree registers no other implementation, so injection points hold the port through an
 * {@code ObjectProvider}. Tests inject a deterministic stub; nothing in this module downloads a
 * model.
 */
public interface ConformanceEncoder {

    /**
     * One embedding per text, index-aligned, from the named checkpoint (the bundle manifest's
     * {@code encoder}, e.g. {@code sentence-transformers/all-MiniLM-L6-v2}). Vectors must be the
     * bundle's {@code embedding_dim} wide. Fail-loud contract, exactly as {@link EncoderScorer}:
     * throw on transport/serving failure or an unknown checkpoint, never silently return zeros:
     * a conformance sweep that scores every head on a zero vector would write confident nonsense.
     */
    List<float[]> encode(String checkpoint, List<String> texts);
}
