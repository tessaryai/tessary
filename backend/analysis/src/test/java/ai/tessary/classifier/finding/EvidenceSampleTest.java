// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A declared sample of evidence rows: the same rows every run, spread over the whole ordered period. */
class EvidenceSampleTest {

    private static List<Integer> range(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    /**
     * Element {@code floor(i * size / n)}: over 1000 rows a 200-row sample is every fifth row, 0 to 995, and over ten
     * rows a 4-row sample is 0, 2, 5 and 7. The bug is a head-truncating sample that presents the first rows as the
     * whole period.
     */
    @Test
    void spreadsAcrossTheWholePeriodNotItsHead() {
        List<Integer> sample = EvidenceSample.spread(range(1000), 200);

        assertEquals(200, sample.size());
        assertEquals(0, sample.get(0));
        assertEquals(995, sample.get(199));
        assertEquals(IntStream.range(0, 200).map(i -> i * 5).boxed().toList(), sample);
        assertEquals(List.of(0, 2, 5, 7), EvidenceSample.spread(range(10), 4));
    }

    /** The bug is padding, repeating or dropping rows when nothing needs sampling. */
    @Test
    void aListNoLargerThanTheSampleIsReturnedWhole() {
        assertEquals(range(5), EvidenceSample.spread(range(5), 200));
        assertEquals(range(5), EvidenceSample.spread(range(5), 5));
        assertEquals(List.of(), EvidenceSample.spread(range(5), 0));
    }
}
