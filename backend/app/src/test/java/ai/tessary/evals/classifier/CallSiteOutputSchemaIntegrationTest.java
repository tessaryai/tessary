// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.substrate.SubstrateReadRepository;
import ai.tessary.evals.pipeline.PipelineRepository;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Round-trips the Malformed Output built-in's schema seam against the real pgvector Postgres
 * (Testcontainers): {@link PipelineRepository#setCallSiteOutputSchema} writes (and null-clears)
 * {@code call_site.output_schema}, and {@link SubstrateReadRepository#callSiteOutputSchemas} reads
 * it back — schema-less call sites absent from the map, empty id set short-circuiting without SQL.
 */
@SpringBootTest
class CallSiteOutputSchemaIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}";

    @Autowired
    PipelineRepository pipelines;

    @Autowired
    SubstrateReadRepository substrate;

    @Autowired
    TenantService tenants;

    @Test
    void outputSchemaRoundTrips_andNullClearsStaleCapture() {
        String pid = TenantFixture.bootstrap(tenants, "cs-schema").project().id();
        pipelines.ensureCallSite(pid, "cs-with-schema");
        pipelines.ensureCallSite(pid, "cs-bare");

        pipelines.setCallSiteOutputSchema(pid, "cs-with-schema", SCHEMA);
        Map<String, String> read = substrate.callSiteOutputSchemas(pid, Set.of("cs-with-schema", "cs-bare"));
        assertEquals(SCHEMA, read.get("cs-with-schema"), "the captured schema reads back verbatim");
        assertFalse(read.containsKey("cs-bare"), "a schema-less call site is absent from the map");

        // Regeneration found the code no longer declares structured output: null clears the capture.
        pipelines.setCallSiteOutputSchema(pid, "cs-with-schema", null);
        assertTrue(
                substrate.callSiteOutputSchemas(pid, Set.of("cs-with-schema")).isEmpty(),
                "a null write clears the stale schema — the call site validates nothing");
    }

    @Test
    void lastWriteWins_newerCaptureReplacesOlder() {
        String pid = TenantFixture.bootstrap(tenants, "cs-schema-lww").project().id();
        pipelines.ensureCallSite(pid, "cs-1");
        pipelines.setCallSiteOutputSchema(pid, "cs-1", "{\"type\":\"object\"}");
        pipelines.setCallSiteOutputSchema(pid, "cs-1", SCHEMA);
        assertEquals(
                SCHEMA,
                substrate.callSiteOutputSchemas(pid, Set.of("cs-1")).get("cs-1"),
                "the newest capture is the current truth");
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
