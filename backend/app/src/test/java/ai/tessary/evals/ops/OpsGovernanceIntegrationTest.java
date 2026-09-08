// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance for the governance tables against the real Spring context (Testcontainers Postgres).
 * This pins the schema + the JdbcClient repositories: each row inserts/upserts and reads back
 * tenant-scoped, and the idempotent upsert collapses a re-observed key to one row.
 */
@SpringBootTest
class OpsGovernanceIntegrationTest {

    @Autowired
    TenantService tenants;

    @Autowired
    RetentionPolicyRepository retentionPolicies;

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private String now() {
        return Instant.now().toString();
    }

    @Test
    void retentionPolicyUpsertIsIdempotentPerSignal() {
        String pid = project("ops-retention");
        retentionPolicies.upsert(
                new RetentionPolicyRow(Ids.ulid(), pid, RetentionPolicyRow.DataClass.TRACES, 30, 7, now(), "{}"));
        retentionPolicies.upsert(
                new RetentionPolicyRow(Ids.ulid(), pid, RetentionPolicyRow.DataClass.TRACES, 90, null, now(), "{}"));

        var rows = retentionPolicies.listByProject(pid);
        assertEquals(1, rows.size());
        assertEquals(90, rows.get(0).ttlDays());
        assertEquals(null, rows.get(0).coldAfterDays());
    }
}
