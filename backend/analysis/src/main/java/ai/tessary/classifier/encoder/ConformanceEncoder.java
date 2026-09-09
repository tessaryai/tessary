// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.encoder;

import ai.tessary.classifier.detector.EncoderScorer;
import java.util.List;

/**
 * Embeds turn text with the frozen sentence encoder the artifact bundle was fitted against — the
 * conformance module's slice of the platform's encoder-serving seam.
 *
 * <p>Same posture as {@link EncoderScorer}, the seam the built-in encoder classifiers
 * (frustration, groundedness) already serve through: the interface names the model
 * ({@code checkpoint} here, {@code head} there) and carries no opinion about who runs it. The
 * production implementation is {@link HttpConformanceEncoder} — the classify-service's
 * {@code POST /embed} endpoint, which serves the fit contract (tokenize to 256 with the
 * checkpoint's own tokenizer, mask mean pooling, L2 normalisation) inside the process built to
 * absorb CPU inference, with the arithmetic pinned there by the same parity fixture that pins this
 * module. {@code OnnxConformanceEncoder} is the in-JVM implementation kept for the parity/smoke
 * harness and as a fallback ({@code tessary.classifier.conformance.encoder-mode=in-jvm}) — it lives in
 * {@code tessary-paid/conformance} since #841, with the ONNX and tokenizer dependencies, so
 * {@link HttpConformanceEncoder} is the OPEN edition's only implementation of this port and
 * {@code in-jvm} registers nothing there. Every open injection point holds this port through an
 * {@code ObjectProvider} for that reason. Tests inject a deterministic stub; nothing in this module
 * downloads a model.
 */
public interface ConformanceEncoder {

    /**
     * One embedding per text, index-aligned, from the named checkpoint (the bundle manifest's
     * {@code encoder}, e.g. {@code sentence-transformers/all-MiniLM-L6-v2}). Vectors must be the
     * bundle's {@code embedding_dim} wide. Fail-loud contract, exactly as {@link EncoderScorer}:
     * throw on transport/serving failure or an unknown checkpoint, never silently return zeros —
     * a conformance sweep that scores every head on a zero vector would write confident nonsense.
     */
    List<float[]> encode(String checkpoint, List<String> texts);
}
