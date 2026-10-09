// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProjectTest {

    /**
     * The bug: a settings blob that does not parse makes {@code isSample} throw, and default-project
     * resolution, which filters on it, answers 500 for an otherwise ordinary project.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {"{\"sample\":true} | true", "{not json | false"})
    void isSampleReadsTheMarkerAndTreatsAnUnreadableBlobAsNotSample(String settings, boolean expected) {
        Project p = new Project("p1", "o1", "demo", "Demo", null, "2026-09-01T00:00:00Z", null, settings, false, null);
        assertEquals(expected, p.isSample());
    }
}
