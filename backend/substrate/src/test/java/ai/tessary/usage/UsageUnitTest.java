// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UsageUnitTest {

    /** Every unit a usage query names on the wire reads back as that unit. */
    @ParameterizedTest
    @EnumSource(UsageUnit.class)
    void everyWireUnitReadsBackAsItself(UsageUnit unit) {
        assertSame(unit, UsageUnit.fromWire(unit.wire()));
    }

    /** A unit the platform does not meter is refused as unknown, not read as some other unit's usage. */
    @Test
    void anUnknownWireUnitIsRefused() {
        TessaryException e = assertThrows(TessaryException.class, () -> UsageUnit.fromWire("tokens_burned"));
        assertEquals(MeteringError.UNKNOWN_UNIT, e.error());
    }
}
