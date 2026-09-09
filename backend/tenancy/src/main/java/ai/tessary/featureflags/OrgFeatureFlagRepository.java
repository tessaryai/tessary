// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CRUD for {@code org_feature_flag}, the per-org capability overrides. Mirrors the
 * JdbcClient + static-mapper shape of {@code OrgQuotaOverrideRepository}.
 *
 * <p>Rows are sparse: an org with no row for a capability has no opinion about it, and the
 * resolution layer above ({@code CapabilityService}) supplies the default. Nothing here ever
 * writes a row to mean "leave it at the default"; {@link #delete} is how an operator gets back
 * there.
 *
 * <p>{@link #findByOrg} loads the whole org at once rather than reading per key, since resolving
 * a capability payload asks about all of them and a per-key read would turn one HTTP request into
 * many round trips. {@code DbFeatureFlags} caches what this returns.
 */
@Repository
public class OrgFeatureFlagRepository {

    private final JdbcClient jdbc;

    public OrgFeatureFlagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every override an org holds, as {@code flagKey → enabled}. The single load the resolution path uses. */
    public Map<String, Boolean> findByOrg(String orgId) {
        return jdbc
                .sql("SELECT flag_key, enabled FROM org_feature_flag WHERE org_id = :oid")
                .param("oid", orgId)
                .query(OrgFeatureFlagRepository::map)
                .list()
                .stream()
                .collect(Collectors.toMap(Row::flagKey, Row::enabled));
    }

    /** The raw rows for an org, in key order: the admin read surface. */
    public List<Row> listByOrg(String orgId) {
        return jdbc.sql("SELECT flag_key, enabled FROM org_feature_flag WHERE org_id = :oid ORDER BY flag_key")
                .param("oid", orgId)
                .query(OrgFeatureFlagRepository::map)
                .list();
    }

    /** Insert or update (by {@code (org_id, flag_key)}) one override, preserving {@code created_at} on update. */
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
     * Drop an org's opinion about one capability, which reverts it to the default. Deliberately
     * distinct from writing {@code false}: "I have not decided" and "I have decided against" resolve the same
     * way for a capability that defaults off, and differently for one that defaults on.
     *
     * @return true if a row was deleted.
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

    /** One override: the {@code Capability.wire()} key and the value the org pinned it to. */
    public record Row(String flagKey, boolean enabled) {}
}
