// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.testsupport.TenantFixture;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrgMembershipRepositoryTest {

    @Autowired
    TenantService tenants;

    @Autowired
    OrgMembershipRepository memberships;

    /**
     * The bug: a membership column, the narrowing {@code scopes} or the {@code attributes} bag above all,
     * is dropped or mis-mapped on its way into or out of the table.
     */
    @Test
    void findByOrg_roundTripsEveryColumn() {
        var fix = TenantFixture.bootstrap(tenants, "membership-roundtrip");
        long n = System.nanoTime();
        Principal u = tenants.upsertUserFromWorkos("user_mrt_" + n, "mrt+" + n + "@example.com", "m", null);
        OrgMembership narrowed = new OrgMembership(
                fix.org().id(), u.id(), "viewer", "[\"traces:read\"]", "{\"team\": \"ml\"}", "2026-09-01T00:00:00Z");

        memberships.insert(narrowed);

        assertEquals(
                Set.of(memberships.find(fix.org().id(), fix.user().id()).orElseThrow(), narrowed),
                Set.copyOf(memberships.findByOrg(fix.org().id())));
    }
}
