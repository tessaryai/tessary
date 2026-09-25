// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ServiceTierTest {

    @Test
    void aStoredValueResolvesIgnoringCaseAndWhitespace() {
        assertEquals(ServiceTier.FLEX, ServiceTier.fromWire(" FLEX "));
    }

    /**
     * The bug: an unknown tier read from a request or a row falls back to some tier and bills the calls
     * at a price nobody chose. It must be refused.
     */
    @Test
    void anUnknownTierIsRefused() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ServiceTier.fromWire("turbo"));

        assertEquals("unknown service tier: turbo", ex.getMessage());
    }
}
