// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.pipeline.PipelineRepository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Round-trips the Malformed Output built-in's schema read seam against the real pgvector Postgres
 * (Testcontainers): {@link SubstrateReadRepository#callSiteOutputSchemas} reads back what
 * {@code call_site.output_schema} holds — schema-less call sites absent from the map, empty id set
 * short-circuiting without SQL.
 */
@SpringBootTest
class CallSiteOutputSchemaIntegrationTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}";

    @Autowired
    PipelineRepository pipelines;

    @Autowired
    SubstrateReadRepository substrate;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    private void storeSchema(String projectId, String callSiteId, @Nullable String schema) {
        jdbc.sql("UPDATE call_site SET output_schema = :schema WHERE project_id = :pid AND id = :id")
                .param("schema", schema)
                .param("pid", projectId)
                .param("id", callSiteId)
                .update();
    }

    @Test
    void outputSchemaRoundTrips_andNullClearsStaleCapture() {
        String pid = TenantFixture.bootstrap(tenants, "cs-schema").project().id();
        pipelines.ensureCallSite(pid, "cs-with-schema");
        pipelines.ensureCallSite(pid, "cs-bare");

        storeSchema(pid, "cs-with-schema", SCHEMA);
        Map<String, String> read = substrate.callSiteOutputSchemas(pid, Set.of("cs-with-schema", "cs-bare"));
        assertEquals(SCHEMA, read.get("cs-with-schema"), "the stored schema reads back verbatim");
        assertFalse(read.containsKey("cs-bare"), "a schema-less call site is absent from the map");

        storeSchema(pid, "cs-with-schema", null);
        assertTrue(
                substrate.callSiteOutputSchemas(pid, Set.of("cs-with-schema")).isEmpty(),
                "a cleared schema reads back as absent — the call site validates nothing");
    }

    @Test
    void emptyIdSetShortCircuitsToEmptyMap() {
        String pid =
                TenantFixture.bootstrap(tenants, "cs-schema-empty").project().id();
        assertTrue(
                substrate.callSiteOutputSchemas(pid, Set.of()).isEmpty(),
                "an empty id set yields an empty map without touching SQL (empty IN () would error)");
    }
}
