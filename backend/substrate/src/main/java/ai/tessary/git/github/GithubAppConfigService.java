// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Loads a manifest-captured GitHub App into {@link GithubAppProperties}. A stored row overwrites
 * {@code TESSARY_GIT_GITHUB_*} on boot. Reader safety comes from {@link GithubAppProperties#applyAll} swapping one
 * {@code volatile} reference; {@code bindLock} only serializes this class's writers.
 */
@Service
public class GithubAppConfigService {

    private static final Logger log = LoggerFactory.getLogger(GithubAppConfigService.class);

    private final GithubAppConfigRepository repo;
    private final GithubAppProperties props;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final Object bindLock = new Object();

    public GithubAppConfigService(
            GithubAppConfigRepository repo, GithubAppProperties props, SecretBox secretBox, ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.secretBox = secretBox;
        this.mapper = mapper;
    }

    /** Rows sealed before the webhook secret was dropped still carry {@code webhookSecret}, hence ignoreUnknown. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CapturedApp(String appId, String privateKeyPem, String appSlug, String clientId, String clientSecret) {}

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        Optional<String> enc = repo.findCredentialsEnc();
        if (enc.isEmpty()) {
            return; // no wizard has ever run: env (or nothing) stays authoritative.
        }
        if (!secretBox.isConfigured()) {
            // No TESSARY_SECRET_KEY: fail closed to env-or-unconfigured rather than crash.
            log.warn("github_app_config row present but SecretBox is not configured; ignoring it at boot");
            return;
        }
        CapturedApp app = open(enc.get());
        applyToProps(app);
        log.info("github app config loaded from github_app_config (appSlug={})", app.appSlug());
    }

    public boolean isAppConfigured() {
        return props.isConfigured();
    }

    /**
     * Refuses once an App is configured: the row is a deployment-wide singleton but the wizard is gated only by org
     * ownership, so any org owner could otherwise overwrite every org's App. To rotate, an operator clears the row
     * first.
     */
    public void persist(String appId, String privateKeyPem, String appSlug, String clientId, String clientSecret) {
        CapturedApp app = new CapturedApp(appId, privateKeyPem, appSlug, clientId, clientSecret);
        String enc = seal(app);
        synchronized (bindLock) {
            // Re-checked under the lock so two racing manifest callbacks can't both write.
            if (props.isConfigured()) {
                throw new TessaryException(GitError.APP_ALREADY_CONFIGURED);
            }
            repo.upsert(enc);
            props.applyAll(app.appId(), app.privateKeyPem(), app.appSlug(), app.clientId(), app.clientSecret());
        }
        log.info("github app config captured via manifest flow (appSlug={})", appSlug);
    }

    private void applyToProps(CapturedApp app) {
        synchronized (bindLock) {
            props.applyAll(app.appId(), app.privateKeyPem(), app.appSlug(), app.clientId(), app.clientSecret());
        }
    }

    private String seal(CapturedApp app) {
        if (!secretBox.isConfigured()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, "github");
        }
        return secretBox.seal(mapper.valueToTree(app).toString());
    }

    private CapturedApp open(String enc) {
        try {
            return mapper.readValue(secretBox.open(enc), CapturedApp.class);
        } catch (Exception e) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, e, "github");
        }
    }
}
