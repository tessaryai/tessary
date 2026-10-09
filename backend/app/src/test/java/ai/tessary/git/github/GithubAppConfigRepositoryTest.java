// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The deployment's one captured GitHub App row: what the manifest wizard writes is what boot reads back,
 * and a second capture replaces the credentials in place rather than failing on the fixed key.
 */
@SpringBootTest
class GithubAppConfigRepositoryTest {

    @Autowired
    GithubAppConfigRepository repo;

    @Autowired
    JdbcClient jdbc;

    private Map<String, Object> row() {
        return jdbc.sql("SELECT id, credentials_enc, created_at FROM github_app_config")
                .query()
                .singleRow();
    }

    @Test
    void upsert_writesTheSingletonRowAndASecondCaptureReplacesItsCredentials() {
        repo.upsert("sealed-first");
        Map<String, Object> first = row();
        assertEquals("default", first.get("id"));
        assertEquals("sealed-first", first.get("credentials_enc"));
        assertEquals(Optional.of("sealed-first"), repo.findCredentialsEnc());

        repo.upsert("sealed-second");

        Map<String, Object> second = row();
        assertEquals("sealed-second", second.get("credentials_enc"));
        assertEquals(first.get("created_at"), second.get("created_at"), "a re-capture keeps the row's creation time");
        assertEquals(Optional.of("sealed-second"), repo.findCredentialsEnc());
    }
}
