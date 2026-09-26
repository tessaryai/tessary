// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import org.junit.jupiter.api.Test;

class ModelLaneTest {

    /**
     * The bug: a typo'd lane in a model-settings PUT silently lands on some default lane and changes the
     * model (and the bill) of a lane the caller never named. It must be a typed 400.
     */
    @Test
    void anUnknownLaneIsATyped400() {
        TessaryException ex = assertThrows(TessaryException.class, () -> ModelLane.fromWire("tirage"));

        assertEquals(ModelConfigError.UNKNOWN_LANE, ex.error());
        assertEquals("Unknown model lane: tirage", ex.getMessage());
    }
}
