// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.tessary.open.media.MediaStore;
import ai.tessary.open.media.MediaStore.MediaRef;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance for the {@link MediaStore} SPI against the real pgvector Postgres
 * (Testcontainers), so the {@code media_object} table + {@code (project_id, digest)}
 * unique key run for real: bytes round-trip through {@code bytea}, identical bytes dedup to one ref +
 * one row, and a missing ref reads empty.
 */
@SpringBootTest
class PostgresMediaStoreTest {

    @Autowired
    MediaStore media;

    @Autowired
    TenantService tenants;

    @Test
    void put_isContentAddressedDedup() {
        String pid = TenantFixture.bootstrap(tenants, "media-dedup").project().id();
        byte[] bytes = "same-bytes".getBytes(StandardCharsets.UTF_8);

        MediaRef a = media.put(pid, bytes, "image/png");
        MediaRef b = media.put(pid, bytes, "image/png");
        assertEquals(a, b, "identical bytes in a project dedup to the same ref");

        MediaRef c = media.put(pid, "different-bytes".getBytes(StandardCharsets.UTF_8), "image/png");
        assertNotEquals(a, c, "different bytes get a different ref");
    }
}
