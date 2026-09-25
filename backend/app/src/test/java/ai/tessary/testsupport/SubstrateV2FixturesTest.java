// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SubstrateV2FixturesTest {

    private static final int DRAWS = 10_000;

    @Test
    void traceId_isUniqueAcrossManyDrawsAt32HexChars() {
        assertDistinctHex(SubstrateV2Fixtures::traceId, 32);
    }

    @Test
    void spanId_isUniqueAcrossManyDrawsAt16HexChars() {
        assertDistinctHex(SubstrateV2Fixtures::spanId, 16);
    }

    private static void assertDistinctHex(Supplier<String> mint, int width) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < DRAWS; i++) {
            String id = mint.get();
            assertTrue(id.matches("[0-9a-f]{" + width + "}"), id);
            seen.add(id);
        }
        assertEquals(DRAWS, seen.size());
    }
}
