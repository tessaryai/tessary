// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code alert_channel} writes, every column. The bugs: a column dropped or swapped on insert or update,
 * an update that rewrites the kind or the creation time, and a write or delete that reaches another
 * project's channel by id.
 */
@SpringBootTest
class AlertChannelRepositoryTest {

    @Autowired
    TenantService tenants;

    @Autowired
    AlertChannelRepository channels;

    @Test
    void everyColumnRoundTripsAndWritesStayInTheirProject() {
        String pid =
                TenantFixture.bootstrap(tenants, "alert-channel-repo").project().id();
        String other = TenantFixture.bootstrap(tenants, "alert-channel-repo-other")
                .project()
                .id();
        AlertChannelRow inserted = new AlertChannelRow(
                Ids.ulid(),
                pid,
                "sentry",
                "errors",
                false,
                "sealed-1",
                "{\"a\": 1}",
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:01Z");

        channels.insert(inserted);

        assertEquals(Optional.of(inserted), channels.find(pid, inserted.id()));
        assertEquals(List.of(inserted), channels.listByProject(pid));

        AlertChannelRow updated = new AlertChannelRow(
                inserted.id(),
                pid,
                "sentry",
                "errors-2",
                true,
                "sealed-2",
                "{\"a\": 2}",
                "2026-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z");
        channels.update(new AlertChannelRow(
                inserted.id(),
                pid,
                "linear",
                "errors-2",
                true,
                "sealed-2",
                "{\"a\": 2}",
                "2027-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z"));
        channels.update(new AlertChannelRow(
                inserted.id(),
                other,
                "sentry",
                "hijack",
                false,
                "sealed-x",
                "{}",
                "2026-01-01T00:00:00Z",
                "2026-03-01T00:00:00Z"));

        assertEquals(
                Optional.of(updated),
                channels.find(pid, inserted.id()),
                "kind and creation time are not rewritten, and another project's write does not land");
        assertEquals(false, channels.delete(other, inserted.id()), "another project cannot delete it");
        assertEquals(true, channels.delete(pid, inserted.id()));
        assertEquals(List.of(), channels.listByProject(pid));
    }
}
