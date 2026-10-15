// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The singleton {@code github_app_config} row, keyed on a fixed sentinel id so {@link #upsert} is a plain upsert. */
@Component
public class GithubAppConfigRepository {

    private static final String ID = "default";

    private final JdbcClient jdbc;

    public GithubAppConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findCredentialsEnc() {
        List<String> rows = jdbc.sql("SELECT credentials_enc FROM github_app_config WHERE id = :id")
                .param("id", ID)
                .query(String.class)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Replaces any prior row. */
    public void upsert(String credentialsEnc) {
        String now = Instant.now().toString();
        jdbc.sql("INSERT INTO github_app_config (id, credentials_enc, created_at, updated_at)"
                        + " VALUES (:id, :enc, :now, :now)"
                        + " ON CONFLICT (id) DO UPDATE SET credentials_enc = :enc, updated_at = :now")
                .param("id", ID)
                .param("enc", credentialsEnc)
                .param("now", now)
                .update();
    }
}
