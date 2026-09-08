// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one {@code github_app_config} row (or none, before the manifest wizard has ever run).
 * Same singleton shape as {@code telemetry_install}/{@link ai.tessary.evals.telemetry.InstallIdRepository}:
 * the table's primary key sits on a fixed sentinel id, so {@link #upsert} is a plain
 * {@code INSERT ... ON CONFLICT DO UPDATE} rather than needing its own "is there already a row"
 * branch — a re-run of the wizard just overwrites the same key.
 */
@Component
public class GithubAppConfigRepository {

    private static final String ID = "default";

    private final JdbcClient jdbc;

    public GithubAppConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The sealed credentials blob, if a self-hoster has ever completed the manifest wizard. */
    public Optional<String> findCredentialsEnc() {
        List<String> rows = jdbc.sql("SELECT credentials_enc FROM github_app_config WHERE id = :id")
                .param("id", ID)
                .query(String.class)
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Seal-and-store the captured App's credentials, replacing any prior row. */
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
