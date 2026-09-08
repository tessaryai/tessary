// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.evals.config.EvalsProperties;
import ai.tessary.evals.crypto.SecretBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DB-row-wins-over-env boot ordering, and {@code persist()}'s live-update of the shared
 * {@link GithubAppProperties} bean — the two behaviors {@link GithubAppConfigService} exists for.
 * The repository is mocked: its own upsert/select SQL has no interesting logic worth an
 * integration test, and this class's contract is entirely about what it does with what the
 * repository hands back.
 */
class GithubAppConfigServiceTest {

    private GithubAppConfigRepository repo;
    private GithubAppProperties props;
    private SecretBox secretBox;
    private ObjectMapper mapper;
    private GithubAppConfigService service;

    @BeforeEach
    void setUp() {
        repo = mock(GithubAppConfigRepository.class);
        props = new GithubAppProperties();
        mapper = new ObjectMapper();
        EvalsProperties p = new EvalsProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 11);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        secretBox = new SecretBox(p);
        service = new GithubAppConfigService(repo, props, secretBox, mapper);
    }

    @Test
    void noStoredRow_leavesEnvBoundPropertiesUntouched() {
        props.setAppId("env-app-id");
        when(repo.findCredentialsEnc()).thenReturn(Optional.empty());

        service.loadOnStartup();

        assertEquals("env-app-id", props.getAppId());
    }

    @Test
    void storedRow_winsOverWhateverEnvBound() {
        props.setAppId("env-app-id"); // simulates EVALS_GIT_GITHUB_APP_ID having been set
        String sealed = seal("byo-app-id", "byo-pem", "byo-hook", "byo-slug", "byo-client", "byo-secret");
        when(repo.findCredentialsEnc()).thenReturn(Optional.of(sealed));

        service.loadOnStartup();

        assertEquals("byo-app-id", props.getAppId());
        assertEquals("byo-pem", props.getPrivateKeyPem());
        assertEquals("byo-hook", props.getWebhookSecret());
        assertEquals("byo-slug", props.getAppSlug());
        assertEquals("byo-client", props.getClientId());
        assertEquals("byo-secret", props.getClientSecret());
    }

    @Test
    void persist_upsertsAndLiveUpdatesTheSameBean_noRestartNeeded() {
        service.persist("new-id", "new-pem", "new-hook", "new-slug", "new-client-id", "new-client-secret");

        // Live-updated immediately: a concurrent authHeader()/isConfigured() call reads this same bean.
        assertEquals("new-id", props.getAppId());
        assertEquals("new-pem", props.getPrivateKeyPem());
        assertTrue(props.isConfigured());

        verify(repo, times(1)).upsert(anyString());
        verify(repo, never()).findCredentialsEnc();
    }

    @Test
    void persist_refusesToOverwriteAnAlreadyConfiguredApp() {
        // github_app_config is a deployment-wide singleton shared by every org; a second wizard run
        // (e.g. a different org's owner) must not be able to silently overwrite it out from under
        // whichever org configured it first.
        props.setAppId("existing-app-id");
        props.setPrivateKeyPem("existing-pem");

        ai.tessary.evals.open.errors.EvalsException e = org.junit.jupiter.api.Assertions.assertThrows(
                ai.tessary.evals.open.errors.EvalsException.class,
                () -> service.persist("new-id", "new-pem", "new-hook", "new-slug", "new-client", "new-secret"));
        assertEquals(ai.tessary.evals.open.errors.GitError.APP_ALREADY_CONFIGURED, e.error());

        // Neither the row nor the live bean moved.
        assertEquals("existing-app-id", props.getAppId());
        assertEquals("existing-pem", props.getPrivateKeyPem());
        verify(repo, never()).upsert(anyString());
    }

    @Test
    void isAppConfigured_reflectsLivePropsState() {
        assertEquals(false, service.isAppConfigured());
        props.setAppId("id");
        props.setPrivateKeyPem("pem");
        assertTrue(service.isAppConfigured());
    }

    private String seal(String appId, String pem, String hook, String slug, String clientId, String clientSecret) {
        try {
            var app = mapper.createObjectNode()
                    .put("appId", appId)
                    .put("privateKeyPem", pem)
                    .put("webhookSecret", hook)
                    .put("appSlug", slug)
                    .put("clientId", clientId)
                    .put("clientSecret", clientSecret);
            return secretBox.seal(mapper.writeValueAsString(app));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
