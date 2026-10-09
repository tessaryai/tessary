// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelCatalogPropertiesTest {

    /** The bug: a {@code tessary.model-catalog.*} duration override binds to the wrong field or not at all. */
    @Test
    void everyKeyBindsToItsOwnField() {
        ModelCatalogProperties p = ConfigBinding.bind(
                "tessary.model-catalog",
                new ModelCatalogProperties(),
                Map.of(
                        "tessary.model-catalog.refresh-interval", "2h",
                        "tessary.model-catalog.fetch-timeout", "30s"));

        assertEquals(Duration.ofHours(2), p.getRefreshInterval());
        assertEquals(Duration.ofSeconds(30), p.getFetchTimeout());
    }
}
