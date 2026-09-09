// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sources;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRepository {

    private static final String COLS =
            "id, project_id, provider, name, base_url, credentials_enc, created_at, updated_at";

    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int countForProject(String projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM ingestion_source WHERE project_id = :pid")
                .param("pid", projectId)
                .query(Integer.class)
                .single();
    }

    public List<SourceRow> findAllForProject(String projectId) {
        return jdbc.sql(String.format(Locale.ROOT, """
            SELECT %s FROM ingestion_source
            WHERE project_id = :pid
            ORDER BY created_at DESC
            """, COLS))
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<SourceRow> findById(String projectId, String id) {
        return jdbc.sql(String.format(Locale.ROOT, """
            SELECT %s FROM ingestion_source WHERE project_id = :pid AND id = :id
            """, COLS))
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** The provider vendor of one source, ignoring project scope. Light projection used to stamp
     *  run provenance on list rows without loading (and decrypting) the full source. */
    public Optional<String> findProvider(String id) {
        return jdbc.sql("SELECT provider FROM ingestion_source WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    public Optional<SourceRow> findByName(String projectId, String name) {
        return jdbc.sql(String.format(Locale.ROOT, """
            SELECT %s FROM ingestion_source WHERE project_id = :pid AND name = :name
            """, COLS))
                .param("pid", projectId)
                .param("name", name)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void insert(SourceRow row) {
        jdbc.sql("""
            INSERT INTO ingestion_source (id, project_id, provider, name, base_url, credentials_enc, created_at, updated_at)
            VALUES (:id, :pid, :provider, :name, :baseUrl, :credentialsEnc, :createdAt, :updatedAt)
            """)
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("provider", row.provider())
                .param("name", row.name())
                .param("baseUrl", row.baseUrl())
                .param("credentialsEnc", row.credentialsEnc())
                .param("createdAt", row.createdAt())
                .param("updatedAt", row.updatedAt())
                .update();
    }

    public boolean deleteById(String projectId, String id) {
        return jdbc.sql("DELETE FROM ingestion_source WHERE project_id = :pid AND id = :id")
                        .param("pid", projectId)
                        .param("id", id)
                        .update()
                > 0;
    }

    private static SourceRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SourceRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("provider"),
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getString("credentials_enc"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
