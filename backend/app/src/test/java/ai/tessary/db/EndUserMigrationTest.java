// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SeedRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Users view's two job kinds against real Postgres. The schedulers insert one row per closed day or hour with
 * {@code ON CONFLICT DO NOTHING}, so a kind missing from {@code job_kind_check} refuses every insert, and a kind with no
 * unique index on {@code dedupe_key} queues the same day again on every tick.
 */
@SpringBootTest
record EndUserMigrationTest(TenantService tenants, JdbcClient jdbc) {

    @Test
    @DisplayName("the job queue takes end_users and end_user_hits, and one pending row per kind and dedupe key")
    void jobKinds_acceptEndUsersAndEndUserHits() {
        Project p = TenantFixture.bootstrap(tenants, "end-user-job-kinds").project();
        String day = p.id() + ":20781";
        String hour = p.id() + ":498744";

        insertJob("daily-" + p.id(), p, "end_users", day);
        insertJob("hits-" + p.id(), p, "end_user_hits", hour);

        assertEquals(
                2,
                jdbc.sql("SELECT count(*) FROM job WHERE project_id = :pid AND kind IN ('end_users', 'end_user_hits')")
                        .param("pid", p.id())
                        .query(Integer.class)
                        .single());
        assertThrows(
                DuplicateKeyException.class,
                () -> insertJob("daily-again-" + p.id(), p, "end_users", day),
                "a second end_users row for the same project day must be refused");
        assertThrows(
                DuplicateKeyException.class,
                () -> insertJob("hits-again-" + p.id(), p, "end_user_hits", hour),
                "a second end_user_hits row for the same project hour must be refused");
    }

    private void insertJob(String id, Project p, String kind, String dedupeKey) {
        SeedRows.insert(jdbc, """
            job | id | project_id | kind | status  | attempts | dedupe_key | payload | created_at | updated_at
                | $1 | $2         | $3   | pending | 0        | $4         | {}      | $5         | $5
            """, id, p.id(), kind, dedupeKey, Instant.now());
    }
}
