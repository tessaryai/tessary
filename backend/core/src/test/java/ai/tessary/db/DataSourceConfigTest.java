// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.config.TessaryProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataSourceConfigTest {

    /**
     * The bug: a deployment with {@code TESSARY_JDBC_URL} unset or blank boots a pool pointed nowhere and
     * fails on the first query, far from the cause. It must refuse to build the pool, naming the variable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankJdbcUrlRefusesToBuildThePool(String url) {
        TessaryProperties props = new TessaryProperties();
        props.setJdbcUrl(url);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new DataSourceConfig().dataSource(props));

        assertEquals(
                "TESSARY_JDBC_URL is required. In dev/prod it is set by docker-compose; in tests it is injected from"
                        + " the Testcontainers pgvector container.",
                ex.getMessage());
    }
}
