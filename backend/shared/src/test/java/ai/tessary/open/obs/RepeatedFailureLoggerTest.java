// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepeatedFailureLoggerTest {

    @Test
    void rejectsNonPositiveSummaryEvery() {
        assertThrows(IllegalArgumentException.class, () -> new RepeatedFailureLogger(0));
        assertThrows(IllegalArgumentException.class, () -> new RepeatedFailureLogger(-1));
    }

    @Test
    void distinctKeysTrackIndependentStreaks() {
        RepeatedFailureLogger logger = new RepeatedFailureLogger(2);
        List<String> firsts = new ArrayList<>();

        logger.record("job-1", () -> firsts.add("job-1"), c -> {});
        logger.record("job-2", () -> firsts.add("job-2"), c -> {});

        assertEquals(List.of("job-1", "job-2"), firsts, "each key gets its own first-occurrence log");
    }

    @Test
    void clearResetsTheStreakSoTheNextFailureLogsFresh() {
        RepeatedFailureLogger logger = new RepeatedFailureLogger(3);
        List<String> firsts = new ArrayList<>();

        logger.record("job-1", () -> firsts.add("first"), c -> {});
        logger.clear("job-1"); // simulates a success between failures
        logger.record("job-1", () -> firsts.add("first"), c -> {});

        assertEquals(List.of("first", "first"), firsts, "a cleared streak logs fresh on its next failure");
    }
}
