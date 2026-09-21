// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import java.util.List;

/**
 * Scores texts against one of the standalone classify-service's built-in encoder heads
 * ({@code /classify}). The seam between a detector (which decides what text to score and how to
 * band the score) and the serving side (transformers.js ONNX heads in the standalone
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

    /**
     * One response for a TOKEN head: every retrieved passage as its own entry (the head lays them out as
     * numbered passages — the layout its checkpoint was trained on, which a joined string cannot
     * recover), the user's question when there is one, and the whole answer as one string.
     */
    record Response(
            List<String> passages,
            @org.jspecify.annotations.Nullable String question,
            String answer) {}

    /**
     * A token head's verdict on one response. {@code unsupported} is the strongest unsupported sentence
     * (P(BASELESS) + P(CONFLICT) at its worst token); {@code conflict} the strongest contradicted one;
     * {@code spans} the per-sentence scores with character offsets into the answer.
     */
    record ResponseScore(double unsupported, double conflict, List<Span> spans) {}

    record Span(int start, int end, double unsupported, double conflict) {}

    /** Token-head scoring: one {@link ResponseScore} per {@link Response}, index-aligned. */
    default List<ResponseScore> scoreResponses(String head, List<Response> responses) {
        throw new UnsupportedOperationException(
                "this EncoderScorer does not implement scoreResponses (head=" + head + ")");
    }
}
