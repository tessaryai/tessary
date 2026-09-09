// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storage for the cross-customer priors pool: the {@code prior_contribution} (aggregate-only) and
 * {@code prior_consent} (per-org opt-in) tables. Deliberately small — the
 * governance logic lives in {@link PriorsGovernance} / {@link PriorsService}; this class only
 * round-trips rows.
 *
 * <p>The two load-bearing reads/writes for governance are here: {@link #consentedContributionsFor}
 * (the cohort scan that feeds the k-anonymity gate — it joins to {@code prior_consent} so a
 * contribution from an org that has revoked consent is invisible to pooling) and
 * {@link #deleteByOrg} (the churn-deletion key path: one statement removes a tenant's entire
 * footprint from the pool).
 */
@Repository
public class PriorContributionRepository {

    private final JdbcClient jdbc;

    public PriorContributionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- consent ---

    public void optIn(String orgId, String at) {
        jdbc.sql("""
            INSERT INTO prior_consent (org_id, opted_in_at) VALUES (:org, :at)
            ON CONFLICT (org_id) DO NOTHING
            """).param("org", orgId).param("at", at).update();
    }

    /** Revoke an org's opt-in. Does NOT delete its contributions — call {@link #deleteByOrg} for
     *  the full churn path; revocation alone simply excludes it from future pooling reads. */
    public void revoke(String orgId) {
        jdbc.sql("DELETE FROM prior_consent WHERE org_id = :org")
                .param("org", orgId)
                .update();
    }

    public boolean hasConsent(String orgId) {
        return jdbc.sql("SELECT 1 FROM prior_consent WHERE org_id = :org")
                .param("org", orgId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    // --- contributions ---

    /** Insert or replace this org's aggregate for a feature bucket (one row per org+feature). */
    public void upsert(PriorContribution c, String at) {
        jdbc.sql("""
            INSERT INTO prior_contribution (id, org_id, feature_key, value, sample_count, content_ref, created_at)
            VALUES (:id, :org, :feature, :value, :samples, :ref, :at)
            ON CONFLICT (org_id, feature_key) DO UPDATE SET
              value = EXCLUDED.value,
              sample_count = EXCLUDED.sample_count,
              content_ref = EXCLUDED.content_ref,
              created_at = EXCLUDED.created_at
            """)
                .param("id", c.contributionId())
                .param("org", c.orgId())
                .param("feature", c.featureKey())
                .param("value", c.value())
                .param("samples", c.sampleCount())
                .param("ref", c.contentRef())
                .param("at", at)
                .update();
    }

    /**
     * All contributions to {@code featureKey} <b>from consenting orgs only</b> — the cohort the
     * k-anonymity gate measures. A contribution whose org has revoked consent is excluded by the
     * inner join, so it neither widens the cohort nor moves the pooled mean.
     */
    public List<PriorContribution> consentedContributionsFor(String featureKey) {
        return jdbc.sql("""
            SELECT pc.* FROM prior_contribution pc
            JOIN prior_consent c ON c.org_id = pc.org_id
            WHERE pc.feature_key = :feature
            ORDER BY pc.org_id
            """)
                .param("feature", featureKey)
                .query(PriorContributionRepository::map)
                .list();
    }

    /** Every contribution from one org (for inspection / deletion verification). */
    public List<PriorContribution> findByOrg(String orgId) {
        return jdbc.sql("SELECT * FROM prior_contribution WHERE org_id = :org ORDER BY feature_key")
                .param("org", orgId)
                .query(PriorContributionRepository::map)
                .list();
    }

    /**
     * Delete a tenant's entire pool footprint — the churn-deletion path. Returns the number of
     * contribution rows removed so callers can log/verify the deletion. Consent is removed too so
     * the org is fully de-pooled.
     */
    public int deleteByOrg(String orgId) {
        revoke(orgId);
        return jdbc.sql("DELETE FROM prior_contribution WHERE org_id = :org")
                .param("org", orgId)
                .update();
    }

    private static PriorContribution map(ResultSet rs, int n) throws SQLException {
        return new PriorContribution(
                rs.getString("id"),
                rs.getString("org_id"),
                rs.getString("feature_key"),
                rs.getDouble("value"),
                rs.getLong("sample_count"),
                rs.getString("content_ref"));
    }
}
