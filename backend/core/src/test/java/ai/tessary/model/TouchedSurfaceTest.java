// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TouchedSurfaceTest {

    /**
     * The bugs: a stored surface written with different case or a hyphen fails to parse and drops out of
     * risk routing; an unknown value (a surface added by a newer writer) throws instead of being skipped.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"Retrieval-RAG, RETRIEVAL_RAG", " prompt , PROMPT", "vector_db, NULL", "'  ', NULL"})
    void parsesWireNamesLeniently(String raw, String expected) {
        assertEquals(Optional.ofNullable(expected).map(TouchedSurface::valueOf), TouchedSurface.parse(raw));
    }
}
