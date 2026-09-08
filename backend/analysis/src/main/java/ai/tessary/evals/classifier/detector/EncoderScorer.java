// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import java.util.List;

/**
 * Scores texts against one of the standalone classify-service's built-in encoder heads
 * ({@code /classify}). The seam between {@link EncoderDetector} (which decides what text to score
 * and how to band the score) and the serving side (transformers.js ONNX heads in the standalone
 * classify-service, ECS Fargate).
 */
public interface EncoderScorer {

    /**
     * One score in {@code [0,1]} per text, index-aligned — the head's "behavior present"
     * probability. Throws on transport/serving failure or when classify-service is unconfigured:
     * an encoder sweep must fail loudly (it is retried on subsequent heartbeats), never
     * silently score everything clean.
     */
    List<Double> score(String head, List<String> texts);

    /**
     * One support score in {@code [0,1]} per (premise, claim) pair, index-aligned — a PAIR head
     * (e.g. {@code groundedness}) scores a claim's support against a premise, not one string in
     * isolation. Same fail-loud contract as {@link #score}.
     *
     * <p>Defaults to throwing: most {@link EncoderScorer} test doubles only stand in for the
     * single-text heads and never exercise a pair head, so they can stay a plain {@code (head,
     * texts) -> ...} lambda without also implementing pair scoring.
     */
    default List<Double> scorePairs(String head, List<Pair> pairs) {
        throw new UnsupportedOperationException("this EncoderScorer does not implement scorePairs (head=" + head + ")");
    }

    /** One (premise, claim) input to a pair head — see {@link #scorePairs}. */
    record Pair(String premise, String claim) {}
}
