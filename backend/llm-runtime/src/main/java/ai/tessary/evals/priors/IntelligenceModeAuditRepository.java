// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.priors;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only store for the intelligence-mode evidence trail ({@code intelligence_mode_audit}).
 * One row per backend boot records the tenancy posture — whether cross-customer
 * pooling was reachable — as the durable, queryable SOC 2 Type II evidence artifact. Deliberately
 * tiny: insert at boot, list for an auditor; never updated or deleted (an evidence log is
 * immutable).
 */
@Repository
public class IntelligenceModeAuditRepository {

    private final JdbcClient jdbc;

    public IntelligenceModeAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record one boot-time tenancy-posture observation. Append-only. */
    public void record(String id, boolean singleTenant, boolean poolingEnabled, String at) {
        jdbc.sql("""
            INSERT INTO intelligence_mode_audit (id, single_tenant, pooling_enabled, recorded_at)
            VALUES (:id, :single, :pooling, :at)
            """)
                .param("id", id)
                .param("single", singleTenant)
                .param("pooling", poolingEnabled)
                .param("at", at)
                .update();
    }

    /** The evidence trail, newest first — what an auditor exports. */
    public List<IntelligenceModeAudit> recent(int limit) {
        return jdbc.sql("SELECT * FROM intelligence_mode_audit ORDER BY recorded_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(IntelligenceModeAuditRepository::map)
                .list();
    }

    private static IntelligenceModeAudit map(ResultSet rs, int n) throws SQLException {
        return new IntelligenceModeAudit(
                rs.getString("id"),
                rs.getBoolean("single_tenant"),
                rs.getBoolean("pooling_enabled"),
                rs.getString("recorded_at"));
    }

    /** One immutable evidence row: the tenancy posture observed at a backend boot. */
    public record IntelligenceModeAudit(String id, boolean singleTenant, boolean poolingEnabled, String recordedAt) {}
}
