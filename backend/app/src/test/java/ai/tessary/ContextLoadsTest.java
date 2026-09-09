// SPDX-License-Identifier: Apache-2.0
package ai.tessary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.sources.SourceDtos.CreateSourceRequest;
import ai.tessary.sources.SourceRow;
import ai.tessary.sources.SourceService;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Smoke test that the Spring context loads end-to-end and tenancy works.
 * Auth is off because {@code TestAuthDisabledInitializer} sets {@code tessary.auth.disabled=true}
 * for every test context, so the filter passes everything through and this exercises the data layer
 * directly.
 */
@SpringBootTest
class ContextLoadsTest {

    @Autowired
    SourceService sourceService;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Test
    void migrationApplied_andRoundTrip() {
        // Tables must exist — fails if Liquibase didn't run.
        Integer count = jdbc.sql("SELECT COUNT(*) FROM ingestion_source")
                .query(Integer.class)
                .single();
        assertEquals(0, count);

        var setup = TenantFixture.bootstrap(tenants, "smoke");

        SourceRow row = sourceService.create(
                setup.project().id(),
                new CreateSourceRequest(SourceService.FAKE_PROVIDER, "test-fake", "fake://upstream", Map.of()));
        assertNotNull(row.id());
        assertEquals(setup.project().id(), row.projectId());

        // Synthetic (non-network) sources carry no secret and open to an empty credentials map.
        Map<String, String> creds =
                sourceService.openCredentials(sourceService.get(setup.project().id(), row.id()));
        assertTrue(creds.isEmpty());
    }
}
