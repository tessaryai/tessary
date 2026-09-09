// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LauncherEncoderScorer#chunk}/{@link LauncherEncoderScorer#chunkPairs}: the
 * pure request-splitting helpers that keep every {@code /classify} POST under the launcher's body
 * cap (bounded by count AND summed UTF-8 bytes) without ever dropping, truncating, or reordering.
 */
class LauncherEncoderScorerTest {

    @Test
    void smallBatchStaysOneChunk() {
        List<List<String>> chunks = LauncherEncoderScorer.chunk(List.of("a", "b", "c"), 100, 1024);
        assertEquals(List.of(List.of("a", "b", "c")), chunks, "a batch under both bounds is a single request");
    }

    @Test
    void splitsByCount() {
        List<String> texts = List.of("a", "b", "c", "d", "e");
        List<List<String>> chunks = LauncherEncoderScorer.chunk(texts, 2, 1024);
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d"), List.of("e")), chunks);
    }

    @Test
    void splitsByByteBudget() {
        // 4-byte texts against a 10-byte budget: two fit, the third starts a new chunk.
        List<String> texts = List.of("aaaa", "bbbb", "cccc", "dddd");
        List<List<String>> chunks = LauncherEncoderScorer.chunk(texts, 100, 10);
        assertEquals(List.of(List.of("aaaa", "bbbb"), List.of("cccc", "dddd")), chunks);
    }

    @Test
    void byteBudgetCountsUtf8BytesNotChars() {
        // U+1F600 is 4 UTF-8 bytes; two 2-char emoji strings (8 bytes each) overflow a 10-byte budget.
        String emoji = "😀😀";
        List<List<String>> chunks = LauncherEncoderScorer.chunk(List.of(emoji, emoji), 100, 10);
        assertEquals(2, chunks.size(), "the budget is UTF-8 bytes, not Java chars");
    }

    @Test
    void oversizedTextGoesAloneUnmodified() {
        String huge = "x".repeat(50);
        List<List<String>> chunks = LauncherEncoderScorer.chunk(List.of("a", huge, "b"), 100, 10);
        assertEquals(
                List.of(List.of("a"), List.of(huge), List.of("b")),
                chunks,
                "a text over the byte budget is its own one-text request, never dropped or truncated");
    }

    @Test
    void oversizedFirstTextGoesAlone() {
        String huge = "x".repeat(50);
        List<List<String>> chunks = LauncherEncoderScorer.chunk(List.of(huge, "a", "b"), 100, 10);
        assertEquals(List.of(List.of(huge), List.of("a", "b")), chunks);
    }

    @Test
    void preservesOrderAndEveryText() {
        List<String> texts = List.of("t0", "t1", "t2", "t3", "t4", "t5", "t6");
        List<List<String>> chunks = LauncherEncoderScorer.chunk(texts, 3, 5);
        List<String> flattened = chunks.stream().flatMap(List::stream).collect(Collectors.toList());
        assertEquals(texts, flattened, "concatenating the chunks reproduces the input exactly, in order");
        assertTrue(chunks.stream().allMatch(c -> c.size() <= 3), "no chunk exceeds the count bound");
    }

    @Test
    void emptyInputYieldsNoChunks() {
        assertEquals(List.of(), LauncherEncoderScorer.chunk(List.of(), 100, 1024), "no texts, no requests");
    }

    // chunkPairs — the pair-head (groundedness) analogue: budget is summed premise+claim UTF-8 bytes.

    @Test
    void pairsSplitByCombinedPremiseAndClaimBytes() {
        EncoderScorer.Pair small = new EncoderScorer.Pair("aa", "bb"); // 4 bytes
        EncoderScorer.Pair alsoSmall = new EncoderScorer.Pair("cc", "dd"); // 4 bytes
        List<List<EncoderScorer.Pair>> chunks = LauncherEncoderScorer.chunkPairs(List.of(small, alsoSmall), 100, 6);
        assertEquals(List.of(List.of(small), List.of(alsoSmall)), chunks, "4+4=8 bytes exceeds the 6-byte budget");
    }

    @Test
    void pairsUnderBudgetStayOneChunk() {
        List<EncoderScorer.Pair> pairs =
                List.of(new EncoderScorer.Pair("premise one", "claim one"), new EncoderScorer.Pair("p2", "c2"));
        assertEquals(List.of(pairs), LauncherEncoderScorer.chunkPairs(pairs, 100, 1024));
    }

    @Test
    void oversizedPairGoesAloneUnmodified() {
        EncoderScorer.Pair huge = new EncoderScorer.Pair("x".repeat(50), "y");
        EncoderScorer.Pair a = new EncoderScorer.Pair("a", "a");
        EncoderScorer.Pair b = new EncoderScorer.Pair("b", "b");
        List<List<EncoderScorer.Pair>> chunks = LauncherEncoderScorer.chunkPairs(List.of(a, huge, b), 100, 10);
        assertEquals(List.of(List.of(a), List.of(huge), List.of(b)), chunks);
    }

    @Test
    void pairsEmptyInputYieldsNoChunks() {
        assertEquals(List.of(), LauncherEncoderScorer.chunkPairs(List.of(), 100, 1024));
    }
}
