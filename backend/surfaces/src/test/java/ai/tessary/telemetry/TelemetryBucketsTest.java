// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.telemetry.TelemetryBuckets.CountBucket;
import ai.tessary.telemetry.TelemetryBuckets.VolumeBucket;
import org.junit.jupiter.api.Test;

/**
 * The closed-enum bucket mapping must serialize to EXACTLY the wire strings
 * devdocs/reference/telemetry-contract.md §1 enumerates — a "coarse bucket" that drifted from the doc
 * would defeat the doc's whole purpose (its own words). Boundary values (the last count in one bucket,
 * the first in the next) are asserted explicitly since an off-by-one there is the failure mode that
 * matters.
 */
class TelemetryBucketsTest {

    @Test
    void countBucketMapsExactlyToTheContractsStrings() {
        assertEquals("0", CountBucket.forCount(0).wire());
        assertEquals("1-5", CountBucket.forCount(1).wire());
        assertEquals("1-5", CountBucket.forCount(5).wire());
        assertEquals("6-25", CountBucket.forCount(6).wire());
        assertEquals("6-25", CountBucket.forCount(25).wire());
        assertEquals("26-100", CountBucket.forCount(26).wire());
        assertEquals("26-100", CountBucket.forCount(100).wire());
        assertEquals("101-500", CountBucket.forCount(101).wire());
        assertEquals("101-500", CountBucket.forCount(500).wire());
        assertEquals("500+", CountBucket.forCount(501).wire());
        assertEquals("500+", CountBucket.forCount(1_000_000).wire());
    }

    @Test
    void countBucketRejectsANegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> CountBucket.forCount(-1));
    }

    @Test
    void volumeBucketMapsExactlyToTheContractsStrings() {
        assertEquals("0", VolumeBucket.forCount(0).wire());
        assertEquals("1-100", VolumeBucket.forCount(1).wire());
        assertEquals("1-100", VolumeBucket.forCount(100).wire());
        assertEquals("101-1k", VolumeBucket.forCount(101).wire());
        assertEquals("101-1k", VolumeBucket.forCount(1_000).wire());
        assertEquals("1k-10k", VolumeBucket.forCount(1_001).wire());
        assertEquals("1k-10k", VolumeBucket.forCount(10_000).wire());
        assertEquals("10k-100k", VolumeBucket.forCount(10_001).wire());
        assertEquals("10k-100k", VolumeBucket.forCount(100_000).wire());
        assertEquals("100k+", VolumeBucket.forCount(100_001).wire());
        assertEquals("100k+", VolumeBucket.forCount(10_000_000).wire());
    }

    @Test
    void volumeBucketRejectsANegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> VolumeBucket.forCount(-1));
    }
}
