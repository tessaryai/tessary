// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** #939 D1: org-keyed, not project-keyed — see {@link ProviderCredential}'s class javadoc. */
@Repository
public class ProviderCredentialRepository {

    private final JdbcClient jdbc;

    public ProviderCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProviderCredential> findByOrgAndProvider(String orgId, ModelProvider provider) {
        return jdbc.sql("SELECT * FROM provider_credential WHERE org_id = :oid AND provider = :prov")
                .param("oid", orgId)
                .param("prov", provider.name())
                .query(ProviderCredentialRepository::map)
                .optional();
    }

    public List<ProviderCredential> findByOrg(String orgId) {
        return jdbc.sql("SELECT * FROM provider_credential WHERE org_id = :oid ORDER BY provider ASC")
                .param("oid", orgId)
                .query(ProviderCredentialRepository::map)
                .list();
    }

    public void insert(ProviderCredential c) {
        jdbc.sql("""
            INSERT INTO provider_credential (
              id, org_id, project_id, provider, base_url_override, api_key_sealed,
              aws_region, aws_access_key_sealed, aws_secret_key_sealed, bedrock_model_arn,
              custom_model_name, auth_mode,
              created_at, updated_at
            ) VALUES (
              :id, :oid, :pid, :prov, :base, :key,
              :reg, :ak, :sk, :arn,
              :cmn, :am,
              :cat, :uat
            )
            """)
                .param("id", c.id())
                .param("oid", c.orgId())
                .param("pid", c.projectId())
                .param("prov", c.provider().name())
                .param("base", c.baseUrlOverride())
                .param("key", c.apiKeySealed())
                .param("reg", c.awsRegion())
                .param("ak", c.awsAccessKeySealed())
                .param("sk", c.awsSecretKeySealed())
                .param("arn", c.bedrockModelArn())
                .param("cmn", c.customModelName())
                .param("am", c.authMode() == null ? ProviderCredential.AUTH_MODE_API_KEY : c.authMode())
                .param("cat", c.createdAt())
                .param("uat", c.updatedAt())
                .update();
    }

    public boolean update(ProviderCredential c) {
        return jdbc.sql("""
            UPDATE provider_credential SET
              base_url_override = :base,
              api_key_sealed = :key,
              aws_region = :reg,
              aws_access_key_sealed = :ak,
              aws_secret_key_sealed = :sk,
              bedrock_model_arn = :arn,
              custom_model_name = :cmn,
              auth_mode = :am,
              updated_at = :uat
            WHERE id = :id
            """)
                        .param("id", c.id())
                        .param("base", c.baseUrlOverride())
                        .param("key", c.apiKeySealed())
                        .param("reg", c.awsRegion())
                        .param("ak", c.awsAccessKeySealed())
                        .param("sk", c.awsSecretKeySealed())
                        .param("arn", c.bedrockModelArn())
                        .param("cmn", c.customModelName())
                        .param("am", c.authMode() == null ? ProviderCredential.AUTH_MODE_API_KEY : c.authMode())
                        .param("uat", c.updatedAt())
                        .update()
                > 0;
    }

    public boolean deleteByOrgAndProvider(String orgId, ModelProvider provider) {
        return jdbc.sql("DELETE FROM provider_credential WHERE org_id = :oid AND provider = :prov")
                        .param("oid", orgId)
                        .param("prov", provider.name())
                        .update()
                > 0;
    }

    private static ProviderCredential map(ResultSet rs, int n) throws SQLException {
        return new ProviderCredential(
                rs.getString("id"),
                rs.getString("org_id"),
                rs.getString("project_id"),
                ModelProvider.valueOf(rs.getString("provider")),
                rs.getString("base_url_override"),
                rs.getString("api_key_sealed"),
                rs.getString("aws_region"),
                rs.getString("aws_access_key_sealed"),
                rs.getString("aws_secret_key_sealed"),
                rs.getString("bedrock_model_arn"),
                rs.getString("custom_model_name"),
                rs.getString("auth_mode"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
