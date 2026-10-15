// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.core.NestedExceptionUtils;

class RedactionPropertiesTest {

    /**
     * The bug: a parallelism of 0 builds a redaction pool with no threads, and ingest hangs on the first
     * batch instead of failing at startup with the key's name.
     */
    @Test
    void aParallelismBelowOneFailsTheBindNamingTheKey() {
        BindException ex = assertThrows(
                BindException.class,
                () -> ConfigBinding.bind(
                        "tessary.redaction", new RedactionProperties(), Map.of("tessary.redaction.parallelism", "0")));

        assertEquals(
                "tessary.redaction.parallelism must be at least 1, not 0",
                NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
    }
}
