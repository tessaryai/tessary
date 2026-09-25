// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpanLatenessTest {

    /**
     * Each bucket's upper edge is exclusive: a span exactly one second late is not "under a second", and a
     * span from a producer whose clock stepped backward is counted apart rather than as on time.
     */
    @ParameterizedTest
    @CsvSource({
        "-1, 0",
        "0, 1",
        "999, 1",
        "1000, 2",
        "9999, 2",
        "10000, 3",
        "59999, 3",
        "60000, 4",
        "299999, 4",
        "300000, 5"
    })
    void aLatenessLandsInTheBucketItsEdgesName(long latenessMillis, int bucket) {
        SpanLateness lateness = new SpanLateness();
        lateness.record(latenessMillis);

        long[] expected = new long[SpanLateness.fields().length];
        expected[bucket] = 1;
        assertArrayEquals(expected, lateness.drain());
        assertArrayEquals(new long[expected.length], lateness.drain(), "a drain resets the interval's counts");
    }
}
