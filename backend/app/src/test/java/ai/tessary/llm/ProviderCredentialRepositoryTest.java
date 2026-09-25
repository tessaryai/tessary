// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code provider_credential} against the real Postgres: every column an update writes comes back, and a
 * delete reaches only the (org, provider) it names. The bug class is a column dropped from the UPDATE,
 * which leaves a credential the settings page shows as changed but every run still reads the old value.
 */
@SpringBootTest
class ProviderCredentialRepositoryTest {

    @Autowired
    TenantService tenants;

    @Autowired
    ProviderCredentialRepository repo;

    @Test
    void updateWritesEveryColumnAndAMissingAuthModeStoresApiKey() {
        String org = TenantFixture.bootstrap(tenants, "cred-update").org().id();
        repo.insert(new ProviderCredential(
                "cred_u",
                org,
                null,
                ModelProvider.BEDROCK,
                "https://old.example.com",
                "old-key",
                "us-east-1",
                "old-ak",
                "old-sk",
                "arn:old",
                "old-name",
                ProviderCredential.AUTH_MODE_IAM_ROLE,
                "t0",
                "t0"));

        boolean updated = repo.update(new ProviderCredential(
                "cred_u",
                org,
                null,
                ModelProvider.BEDROCK,
                "https://new.example.com",
                "new-key",
                "eu-west-1",
                "new-ak",
                "new-sk",
                "arn:new",
                "new-name",
                null,
                "ignored",
                "t1"));

        assertTrue(updated);
        assertEquals(
                Optional.of(new ProviderCredential(
                        "cred_u",
                        org,
                        null,
                        ModelProvider.BEDROCK,
                        "https://new.example.com",
                        "new-key",
                        "eu-west-1",
                        "new-ak",
                        "new-sk",
                        "arn:new",
                        "new-name",
                        ProviderCredential.AUTH_MODE_API_KEY,
                        "t0",
                        "t1")),
                repo.findByOrgAndProvider(org, ModelProvider.BEDROCK),
                "every column but the creation time is rewritten");
    }

    @Test
    void updateOfARowThatIsGoneReportsNothingUpdated() {
        String org = TenantFixture.bootstrap(tenants, "cred-update-gone").org().id();

        assertFalse(repo.update(new ProviderCredential(
                "cred_missing",
                org,
                null,
                ModelProvider.OPENAI,
                null,
                "k",
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                "t0",
                "t1")));
    }

    @Test
    void deleteRemovesOnlyTheNamedProviderOfTheNamedOrg() {
        String org = TenantFixture.bootstrap(tenants, "cred-delete").org().id();
        String other =
                TenantFixture.bootstrap(tenants, "cred-delete-other").org().id();
        repo.insert(apiKey("c_openai", org, ModelProvider.OPENAI));
        repo.insert(apiKey("c_anthropic", org, ModelProvider.ANTHROPIC));
        repo.insert(apiKey("c_other", other, ModelProvider.OPENAI));

        assertTrue(repo.deleteByOrgAndProvider(org, ModelProvider.OPENAI));
        assertFalse(repo.deleteByOrgAndProvider(org, ModelProvider.OPENAI), "a second delete finds nothing");

        assertEquals(
                List.of("c_anthropic"),
                repo.findByOrg(org).stream().map(ProviderCredential::id).toList());
        assertEquals(
                List.of("c_other"),
                repo.findByOrg(other).stream().map(ProviderCredential::id).toList(),
                "another org's key for the same provider is untouched");
    }

    private static ProviderCredential apiKey(String id, String org, ModelProvider provider) {
        return new ProviderCredential(
                id,
                org,
                null,
                provider,
                null,
                "sealed",
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                "t0",
                "t0");
    }
}
