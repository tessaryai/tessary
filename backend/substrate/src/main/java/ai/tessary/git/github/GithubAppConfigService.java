// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Loads a self-hoster's manifest-captured GitHub App into the live {@link GithubAppProperties}
 * bean, and persists a newly-captured one from {@link GithubManifestController}'s callback.
 *
 * <p>A stored row wins over env when present: on {@link ApplicationReadyEvent}, a stored row
 * overwrites whatever {@code TESSARY_GIT_GITHUB_*} handed {@link GithubAppProperties} at
 * Spring-binding time. This is deliberate: a self-hoster who ran the wizard has a DB row and no env
 * vars, and env vars stay authoritative for a deployment that never runs the wizard (no row, nothing
 * to overwrite). An operator who sets env vars without clearing a leftover DB row will find the row
 * keeps winning, same as it did the first time; worth flagging if this ever trips someone up in an
 * incident.
 *
 * <p>The live-update path ({@link #persist}) replaces {@link GithubAppProperties}'s six credential
 * fields outside Spring's own binding lifecycle, via {@link GithubAppProperties#applyAll}, which
 * swaps them behind one {@code volatile} reference. That is what actually protects a concurrent
 * {@code isConfigured()}/{@code authHeader()} read (e.g. {@link GithubTokenService}, or
 * {@link ai.tessary.synth.SignalResolver} in a different module entirely) from ever observing
 * a torn partial write: those readers call {@link GithubAppProperties}'s plain getters with no
 * synchronization of their own, so the guarantee has to live on the write side. The private
 * {@code bindLock} below only serializes this class's own writers against each other ({@link
 * #loadOnStartup} racing a concurrent {@link #persist}); it says nothing about reader safety.
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

    /** JSON shape sealed into {@code github_app_config.credentials_enc}. */
    record CapturedApp(
            String appId,
            String privateKeyPem,
            String webhookSecret,
            String appSlug,
            String clientId,
            String clientSecret) {}

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        Optional<String> enc = repo.findCredentialsEnc();
        if (enc.isEmpty()) {
            return; // no wizard has ever run: env (or nothing) stays authoritative.
        }
        if (!secretBox.isConfigured()) {
            // A row exists but we can't open it (no TESSARY_SECRET_KEY). Fail closed to
            // env-or-unconfigured rather than crash the whole app on a missing key that may be
            // perfectly fine for a deployment that never captured a BYO App.
            log.warn("github_app_config row present but SecretBox is not configured; ignoring it at boot");
            return;
        }
        CapturedApp app = open(enc.get());
        applyToProps(app);
        log.info("github app config loaded from github_app_config (appSlug={})", app.appSlug());
    }

    /** True once an App is live: from a prior wizard run (DB row) or from {@code TESSARY_GIT_GITHUB_*} env vars. */
    public boolean isAppConfigured() {
        return props.isConfigured();
    }

    /**
     * Called by {@link GithubManifestController}'s callback once GitHub's manifest conversion
     * returns the new App's own credentials. Seals and upserts the row, then live-updates the same
     * {@link GithubAppProperties} bean the rest of the process already reads, no restart needed.
     *
     * <p>Refuses once an App is already configured. {@code github_app_config} is a deployment-wide
     * singleton (one App shared by every org on this instance, same as env-configured App credentials
     * always have, see {@link GithubManifestController}'s class javadoc), but the wizard itself is
     * gated only by ownership of the calling org/project ({@link GithubManifestController#manifestUrl}),
     * not by any deployment-admin role. Without this guard, any org owner on a multi-org self-hosted
     * instance could silently overwrite every other org's shared App credentials mid-flight, breaking
     * their App-mode token minting. First-run-only turns that into a fixed setup step instead of a
     * standing cross-tenant overwrite surface; a deployment operator who genuinely wants to rotate the
     * App clears the {@code github_app_config} row (or the env vars) themselves first.
     */
    public void persist(
            String appId,
            String privateKeyPem,
            String webhookSecret,
            String appSlug,
            String clientId,
            String clientSecret) {
        CapturedApp app = new CapturedApp(appId, privateKeyPem, webhookSecret, appSlug, clientId, clientSecret);
        String enc = seal(app);
        synchronized (bindLock) {
            // Re-checked under the same lock applyToProps writes under, so two concurrent manifest
            // callbacks (e.g. two org owners racing the wizard) can't both pass the check and both
            // write: the second one loses to this guard instead of silently overwriting the first.
            if (props.isConfigured()) {
                throw new TessaryException(GitError.APP_ALREADY_CONFIGURED);
            }
            repo.upsert(enc);
            props.applyAll(
                    app.appId(),
                    app.privateKeyPem(),
                    app.webhookSecret(),
                    app.appSlug(),
                    app.clientId(),
                    app.clientSecret());
        }
        log.info("github app config captured via manifest flow (appSlug={})", appSlug);
    }

    private void applyToProps(CapturedApp app) {
        synchronized (bindLock) {
            props.applyAll(
                    app.appId(),
                    app.privateKeyPem(),
                    app.webhookSecret(),
                    app.appSlug(),
                    app.clientId(),
                    app.clientSecret());
        }
    }

    private String seal(CapturedApp app) {
        if (!secretBox.isConfigured()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, "github");
        }
        try {
            return secretBox.seal(mapper.writeValueAsString(app));
        } catch (Exception e) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, e, "github");
        }
    }

    private CapturedApp open(String enc) {
        try {
            return mapper.readValue(secretBox.open(enc), CapturedApp.class);
        } catch (Exception e) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, e, "github");
        }
    }
}
