// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The device_link table through its repository: every column survives a write and both lookups,
 * and each conditional transition changes exactly one row while its precondition holds and none
 * after, which is what keeps a confirm/deny race and a concurrent poll from both winning.
 */
@SpringBootTest
class DeviceLinkRepositoryTest {

    @Autowired
    DeviceLinkRepository repo;

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService keys;

    /**
     * A row with every column populated, pointing at real org, project, user and key rows (the
     * table's foreign keys), with unique lookup keys since every test class in this context shares it.
     */
    private DeviceLink populated(TenantFixture.Setup fix, int pollCount) {
        String n = Ids.ulid();
        String tokenId = keys.issue(fix.project().id(), fix.user().id(), "link test")
                .token()
                .id();
        return new DeviceLink(
                "dl_" + n,
                "link_" + n.substring(n.length() - 8),
                "$2a$10$hash-" + n,
                n.substring(n.length() - 9),
                DeviceLink.PENDING,
                "laptop",
                fix.org().id(),
                fix.project().id(),
                fix.user().id(),
                tokenId,
                "2026-09-25T12:00:00Z",
                "2026-09-25T12:10:00Z",
                "2026-09-25T12:01:00Z",
                pollCount);
    }

    private DeviceLink reread(DeviceLink r) {
        return repo.findByPrefix(r.deviceCodePrefix()).orElseThrow();
    }

    @Test
    void insert_roundTripsEveryColumnThroughBothLookups() {
        DeviceLink r = populated(TenantFixture.bootstrap(tenants, "devlink-roundtrip"), 7);
        repo.insert(r);

        assertEquals(Optional.of(r), repo.findByPrefix(r.deviceCodePrefix()));
        assertEquals(Optional.of(r), repo.findByUserCode(r.userCode()));
        assertEquals(Optional.empty(), repo.findByUserCode("NOPE-NOPE"));
    }

    @Test
    void confirmThenClaim_eachAppliesOnceFromItsExpectedState() {
        TenantFixture.Setup origin = TenantFixture.bootstrap(tenants, "devlink-claim");
        DeviceLink r = populated(origin, 0);
        repo.insert(r);
        TenantFixture.Setup target = TenantFixture.bootstrap(tenants, "devlink-target");
        String minted = keys.issue(target.project().id(), target.user().id(), "minted")
                .token()
                .id();

        assertEquals(
                1,
                repo.markConfirmed(
                        r.id(),
                        target.org().id(),
                        target.project().id(),
                        target.user().id()));
        assertEquals(
                0,
                repo.markConfirmed(
                        r.id(),
                        origin.org().id(),
                        origin.project().id(),
                        origin.user().id()),
                "confirm only while pending");
        assertEquals(0, repo.transitionIf(r.id(), DeviceLink.PENDING, DeviceLink.DENIED), "deny lost the race");
        assertEquals(
                new DeviceLink(
                        r.id(),
                        r.deviceCodePrefix(),
                        r.deviceCodeHash(),
                        r.userCode(),
                        DeviceLink.CONFIRMED,
                        "laptop",
                        target.org().id(),
                        target.project().id(),
                        target.user().id(),
                        r.mcpTokenId(),
                        r.createdAt(),
                        r.expiresAt(),
                        r.lastPolledAt(),
                        0),
                reread(r));

        assertEquals(1, repo.beginClaim(r.id()));
        assertEquals(0, repo.beginClaim(r.id()), "only one poll may win the claim");
        repo.setToken(r.id(), minted);
        repo.recordPoll(r.id(), "2026-09-25T12:02:00Z", 3);

        DeviceLink claimed = reread(r);
        assertEquals(DeviceLink.CLAIMED, claimed.status());
        assertEquals(minted, claimed.mcpTokenId());
        assertEquals("2026-09-25T12:02:00Z", claimed.lastPolledAt());
        assertEquals(3, claimed.pollCount());
    }

    @Test
    void denyAndExpire_moveTheRowAndLeaveEveryOtherColumn() {
        DeviceLink r = populated(TenantFixture.bootstrap(tenants, "devlink-deny"), 2);
        repo.insert(r);

        assertEquals(1, repo.transitionIf(r.id(), DeviceLink.PENDING, DeviceLink.DENIED));
        assertEquals(0, repo.beginClaim(r.id()), "a denied link can never be claimed");
        repo.markStatus(r.id(), DeviceLink.EXPIRED);

        assertEquals(
                new DeviceLink(
                        r.id(),
                        r.deviceCodePrefix(),
                        r.deviceCodeHash(),
                        r.userCode(),
                        DeviceLink.EXPIRED,
                        r.clientLabel(),
                        r.orgId(),
                        r.projectId(),
                        r.userId(),
                        r.mcpTokenId(),
                        r.createdAt(),
                        r.expiresAt(),
                        r.lastPolledAt(),
                        2),
                reread(r));
    }
}
