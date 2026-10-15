// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import java.util.List;

/** The seam between a detector and the encoder's {@code /classify} token head. */
public interface EncoderScorer {

    /** Passages stay separate: the head was trained on numbered passages, which a joined string cannot recover. */
    record Response(
            List<String> passages,
            @org.jspecify.annotations.Nullable String question,
            String answer) {}

    /**
     * {@code unsupported} is the strongest unsupported sentence (P(BASELESS) + P(CONFLICT) at its worst token);
     * {@code spans} carry character offsets into the answer.
     */
    record ResponseScore(double unsupported, double conflict, List<Span> spans) {
        /**
         * A 400 for one response (too long for the window), as NaN so the caller skips it; never 0.0, which reads as
         * clean.
         */
        public static final ResponseScore UNSCORED = new ResponseScore(Double.NaN, Double.NaN, List.of());

        public boolean scored() {
            return !Double.isNaN(unsupported);
        }
    }

    record Span(int start, int end, double unsupported, double conflict) {}

    /**
     * Index-aligned. Throws on transport failure or when unconfigured: an encoder sweep must fail loudly, never score
     * everything clean.
     */
    List<ResponseScore> scoreResponses(String head, List<Response> responses);
}
