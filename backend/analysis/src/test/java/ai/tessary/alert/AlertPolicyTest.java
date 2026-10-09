// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The case-opened rule's notification policy. Every misreading fails as a partner not paged and unaware, so each row
 * names such an input.
 */
class AlertPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Quiet windows in the rule's zone: a midnight-wrapping window read as its complement, an inclusive end, equal
     * ends as 24 hours of silence, or UTC instead of the partner's zone.
     */
    @ParameterizedTest(name = "{0}-{1} {2} at {3} -> quiet={4}")
    @CsvSource({
        "09:00, 17:00, UTC, 2026-01-15T08:59:00Z, false",
        "09:00, 17:00, UTC, 2026-01-15T09:00:00Z, true",
        "09:00, 17:00, UTC, 2026-01-15T16:59:00Z, true",
        "09:00, 17:00, UTC, 2026-01-15T17:00:00Z, false",
        "22:00, 08:00, UTC, 2026-01-15T23:00:00Z, true",
        "22:00, 08:00, UTC, 2026-01-15T03:00:00Z, true",
        "22:00, 08:00, UTC, 2026-01-15T08:00:00Z, false",
        "22:00, 08:00, UTC, 2026-01-15T12:00:00Z, false",
        "09:00, 09:00, UTC, 2026-01-15T09:00:00Z, false",
        // 02:00Z is 21:00 the evening before in New York.
        "20:00, 23:00, America/New_York, 2026-01-16T02:00:00Z, true",
        "20:00, 23:00, America/New_York, 2026-01-15T21:00:00Z, false",
    })
    void isQuietReadsTheWindowInItsOwnZone(String from, String to, String zone, String now, boolean quiet) {
        AlertPolicy policy = new AlertPolicy(0, LocalTime.parse(from), LocalTime.parse(to), ZoneId.of(zone));

        assertEquals(quiet, policy.isQuiet(Instant.parse(now)));
    }

    /**
     * The lenient parser for API and stored blob: an unreadable part degrades to permissive, never quiet forever or
     * throwing on the heartbeat.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rawPolicies")
    void ofDegradesEveryUnreadablePartToItsPermissiveDefault(
            String why,
            long cadence,
            @Nullable String from,
            @Nullable String to,
            @Nullable String zone,
            AlertPolicy expected) {
        assertEquals(expected, AlertPolicy.of(cadence, from, to, zone));
    }

    static Stream<Arguments> rawPolicies() {
        ZoneId utc = ZoneId.of("UTC");
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        LocalTime ten = LocalTime.of(22, 0);
        LocalTime eight = LocalTime.of(8, 0);
        return Stream.of(
                Arguments.of(
                        "a full window is kept, cadence capped at a day",
                        90_000L,
                        "22:00",
                        "08:00",
                        "Europe/Berlin",
                        new AlertPolicy(86_400, ten, eight, berlin)),
                Arguments.of(
                        "a negative cadence is every tick", -5L, null, null, null, new AlertPolicy(0, null, null, utc)),
                Arguments.of(
                        "a window with no end is no window",
                        60L,
                        "22:00",
                        null,
                        "UTC",
                        new AlertPolicy(60, null, null, utc)),
                Arguments.of(
                        "an unparseable start is no window",
                        60L,
                        "25:99",
                        "08:00",
                        "UTC",
                        new AlertPolicy(60, null, null, utc)),
                Arguments.of(
                        "a blank start is no window", 60L, "  ", "08:00", "UTC", new AlertPolicy(60, null, null, utc)),
                Arguments.of(
                        "an unknown zone reads as UTC",
                        60L,
                        "22:00",
                        "08:00",
                        "Mars/Olympus_Mons",
                        new AlertPolicy(60, ten, eight, utc)),
                Arguments.of(
                        "a blank zone reads as UTC", 60L, "22:00", "08:00", " ", new AlertPolicy(60, ten, eight, utc)));
    }

    /** A corrupt blob fails open to notifying every tick. */
    @Test
    void anUnreadableAttributesBlobReadsAsImmediate() {
        assertEquals(AlertPolicy.IMMEDIATE, AlertPolicy.of(mapper, "{not json"));
    }

    /** Cadence is minimum spacing: exactly one cadence later is due, a second less is not. */
    @ParameterizedTest(name = "cadence {0}s, {1}s after last -> {2}")
    @CsvSource({"0, 0, true", "300, 299, false", "300, 300, true"})
    void cadenceElapsedAtExactlyOneCadence(long cadence, long secondsSince, boolean elapsed) {
        Instant last = Instant.parse("2026-01-15T10:00:00Z");
        AlertPolicy policy = new AlertPolicy(cadence, null, null, ZoneId.of("UTC"));

        assertEquals(elapsed, policy.cadenceElapsed(last, last.plusSeconds(secondsSince)));
    }
}
