// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import java.util.List;

/**
 * Scores responses against an encoder's token head ({@code /classify}). The seam between a detector
 * (which decides what to score and how to band the score) and the serving side.
 */
public interface EncoderScorer {

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
    record ResponseScore(double unsupported, double conflict, List<Span> spans) {
        /**
         * The encoder refused this one response (a 400: an answer too long for the head's window).
         * Carried in the aligned list as NaN so the caller can skip exactly it; never a 0.0, which
         * would read as "clean".
         */
        public static final ResponseScore UNSCORED = new ResponseScore(Double.NaN, Double.NaN, List.of());

        public boolean scored() {
            return !Double.isNaN(unsupported);
        }
    }

    record Span(int start, int end, double unsupported, double conflict) {}

    /**
     * Token-head scoring: one {@link ResponseScore} per {@link Response}, index-aligned. Throws on
     * transport/serving failure or when the encoder is unconfigured: an encoder sweep must fail loudly
     * (it is retried on subsequent heartbeats), never silently score everything clean.
     */
    List<ResponseScore> scoreResponses(String head, List<Response> responses);
}
