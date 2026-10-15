// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CRUD for {@code org_feature_flag}. Rows are sparse: no row means no opinion, and {@code CapabilityService} supplies
 * the default; {@link #delete}, never a written value, returns to it. {@link #findByOrg} loads the whole org at once,
 * cached by {@code DbFeatureFlags}.
 */
@Repository
public class OrgFeatureFlagRepository {

    private final JdbcClient jdbc;

    public OrgFeatureFlagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Boolean> findByOrg(String orgId) {
        return jdbc
                .sql("SELECT flag_key, enabled FROM org_feature_flag WHERE org_id = :oid")
                .param("oid", orgId)
                .query(OrgFeatureFlagRepository::map)
                .list()
                .stream()
                .collect(Collectors.toMap(Row::flagKey, Row::enabled));
    }

    /** Preserves {@code created_at} on update. */
    public void upsert(String orgId, String flagKey, boolean enabled) {
        String now = Instant.now().toString();
        jdbc.sql("""
            INSERT INTO org_feature_flag (org_id, flag_key, enabled, created_at, updated_at)
            VALUES (:oid, :fkey, :enabled, :now, :now)
            ON CONFLICT (org_id, flag_key) DO UPDATE
               SET enabled = :enabled, updated_at = :now
            """)
                .param("oid", orgId)
                .param("fkey", flagKey)
                .param("enabled", enabled)
                .param("now", now)
                .update();
    }

    /**
     * Distinct from writing {@code false}: "undecided" and "decided against" differ for a capability that defaults on.
     * @return true if a row was deleted
     */
    public boolean delete(String orgId, String flagKey) {
        return jdbc.sql("DELETE FROM org_feature_flag WHERE org_id = :oid AND flag_key = :fkey")
                        .param("oid", orgId)
                        .param("fkey", flagKey)
                        .update()
                > 0;
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getString("flag_key"), rs.getBoolean("enabled"));
    }

    public record Row(String flagKey, boolean enabled) {}
}
