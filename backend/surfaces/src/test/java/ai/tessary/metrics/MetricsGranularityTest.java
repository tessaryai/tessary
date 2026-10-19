// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MetricsGranularityTest {

    /** The bug: a three-day window drawn as three day bars, or a four-day window drawn as 96 hour bars. */
    @Test
    @DisplayName("auto picks hours up to 72 hours and days past it")
    void autoIsHourUpToSeventyTwoHoursAndDayAfter() {
        assertEquals(MetricsGranularity.HOUR, MetricsGranularity.autoFor(Duration.ofHours(24)));
        assertEquals(MetricsGranularity.HOUR, MetricsGranularity.autoFor(Duration.ofHours(72)));
        assertEquals(
                MetricsGranularity.DAY,
                MetricsGranularity.autoFor(Duration.ofHours(72).plusSeconds(1)));
    }

    /** The bug: an axis that starts at `from` instead of its hour drops the partial first bucket. */
    @ParameterizedTest
    @CsvSource({
        "2026-10-11T00:00:00Z, 2026-10-18T00:00:00Z, 168",
        "2026-10-11T00:30:00Z, 2026-10-18T00:30:00Z, 169",
    })
    @DisplayName("hour buckets run from the truncated start and stop before to")
    void hourBucketsCoverTheWindowFromTheTruncatedStart(String from, String to, int count) {
        List<Instant> buckets = MetricsGranularity.HOUR.buckets(Instant.parse(from), Instant.parse(to));
        assertEquals(count, buckets.size());
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), buckets.get(0));
        assertEquals(Instant.parse("2026-10-11T01:00:00Z"), buckets.get(1));
    }

    /** The bug: a window that ends on midnight grows an empty bucket for the day after it. */
    @ParameterizedTest
    @CsvSource({
        "2026-10-11T00:00:00Z, 2026-10-18T00:00:00Z, 7, 2026-10-17T00:00:00Z",
        "2026-10-11T12:00:00Z, 2026-10-18T12:00:00Z, 8, 2026-10-18T00:00:00Z",
        "2026-10-11T00:00:00Z, 2026-10-18T00:00:00.000001Z, 8, 2026-10-18T00:00:00Z",
    })
    @DisplayName("day buckets end with the day that holds the window's last microsecond")
    void dayBucketsEndBeforeTo(String from, String to, int count, String last) {
        List<Instant> buckets = MetricsGranularity.DAY.buckets(Instant.parse(from), Instant.parse(to));
        assertEquals(count, buckets.size());
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), buckets.get(0));
        assertEquals(Instant.parse(last), buckets.get(buckets.size() - 1));
    }

    /** The bug: weeks that start on Sunday, or in local time, disagree with Postgres date_trunc('week', x, 'UTC'). */
    @Test
    @DisplayName("week buckets start on Monday 00:00 UTC")
    void weekBucketsStartOnMondayUtc() {
        assertEquals(
                Instant.parse("2026-10-12T00:00:00Z"),
                MetricsGranularity.WEEK.truncate(Instant.parse("2026-10-18T23:59:59Z")));
        assertEquals(
                Instant.parse("2026-10-12T00:00:00Z"),
                MetricsGranularity.WEEK.truncate(Instant.parse("2026-10-12T00:00:00Z")));
        assertEquals(
                List.of(
                        Instant.parse("2026-09-28T00:00:00Z"),
                        Instant.parse("2026-10-05T00:00:00Z"),
                        Instant.parse("2026-10-12T00:00:00Z")),
                MetricsGranularity.WEEK.buckets(
                        Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-19T00:00:00Z")));
    }

    /** The bug: a known unit misread, or a missing granularity read as an axis. */
    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {"auto, AUTO", "hour, HOUR", "day, DAY", "week, WEEK", "null, null"})
    @DisplayName("fromWire accepts the four units and null")
    void fromWireAcceptsTheFourUnitsAndNull(@Nullable String wire, @Nullable MetricsGranularity expected) {
        assertEquals(expected, MetricsGranularity.fromWire(wire));
    }

    /** The bug: an unknown unit read as no axis, so a typo silently turns a chart into a number. */
    @ParameterizedTest
    @ValueSource(strings = {"month", "HOUR", ""})
    @DisplayName("fromWire refuses anything else, naming it")
    void fromWireRefusesTheRest(String wire) {
        TessaryException ex = assertThrows(TessaryException.class, () -> MetricsGranularity.fromWire(wire));
        assertEquals(MetricsError.UNKNOWN_GRANULARITY, ex.error());
        assertEquals("Granularity '" + wire + "' is not one of auto, hour, day, week", ex.getMessage());
    }

    /** The bug: an unresolved auto bucketed as if it were a unit. */
    @Test
    @DisplayName("auto cannot bucket")
    void autoCannotBucket() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> MetricsGranularity.AUTO.truncate(Instant.EPOCH));
        assertEquals("auto is resolved before bucketing", ex.getMessage());
    }
}
