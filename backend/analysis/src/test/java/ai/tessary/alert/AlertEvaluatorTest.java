// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The snooze horizon. The bugs: a horizon that mutes after it has passed, and an unparseable one (the
 * snooze endpoint stores whatever it is sent) that mutes forever or throws on the heartbeat instead of
 * failing open to firing.
 */
class AlertEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @ParameterizedTest(name = "snoozed_until=''{0}'' -> {1}")
    @CsvSource(
            value = {
                "NULL, false",
                "'', false",
                "'   ', false",
                "2026-01-15T10:00:01Z, true",
                "2026-01-15T10:00:00Z, false",
                "2026-01-15T09:59:59Z, false",
                "tomorrow, false",
            },
            nullValues = "NULL")
    void onlyAFutureParseableHorizonMutes(@Nullable String snoozedUntil, boolean snoozed) {
        assertEquals(snoozed, AlertEvaluator.isSnoozed(snoozedUntil, NOW));
    }
}
