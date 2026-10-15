// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tessary.git.github")
public class GithubAppProperties {

    /** GHES installs override per integration. */
    public static final String DEFAULT_API_HOST = "api.github.com";

    /**
     * Behind one {@code volatile} reference so a live update via {@link #applyAll} swaps all five fields atomically for
     * unsynchronized readers such as {@code GithubTokenService.authHeader()}. The individual setters are only called by
     * Spring's binder at startup, before any reader exists.
     */
    private record Snapshot(String appId, String privateKeyPem, String appSlug, String clientId, String clientSecret) {
        static final Snapshot EMPTY = new Snapshot("", "", "", "", "");
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public String getAppId() {
        return snapshot.appId();
    }

    public void setAppId(String appId) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(appId, s.privateKeyPem(), s.appSlug(), s.clientId(), s.clientSecret());
    }

    public String getClientId() {
        return snapshot.clientId();
    }

    public void setClientId(String clientId) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(s.appId(), s.privateKeyPem(), s.appSlug(), clientId, s.clientSecret());
    }

    public String getClientSecret() {
        return snapshot.clientSecret();
    }

    public void setClientSecret(String clientSecret) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(s.appId(), s.privateKeyPem(), s.appSlug(), s.clientId(), clientSecret);
    }

    /** OAuth client credentials are required to verify the installer's identity. */
    public boolean hasOAuth() {
        Snapshot s = snapshot;
        return s.clientId() != null
                && !s.clientId().isBlank()
                && s.clientSecret() != null
                && !s.clientSecret().isBlank();
    }

    public String getAppSlug() {
        return snapshot.appSlug();
    }

    public void setAppSlug(String appSlug) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(s.appId(), s.privateKeyPem(), appSlug, s.clientId(), s.clientSecret());
    }

    public String getPrivateKeyPem() {
        return snapshot.privateKeyPem();
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        Snapshot s = snapshot;
        snapshot = new Snapshot(s.appId(), privateKeyPem, s.appSlug(), s.clientId(), s.clientSecret());
    }

    public boolean isConfigured() {
        Snapshot s = snapshot;
        return s.appId() != null
                && !s.appId().isBlank()
                && s.privateKeyPem() != null
                && !s.privateKeyPem().isBlank();
    }

    /** Atomic swap of all five fields; see {@link Snapshot}. */
    void applyAll(String appId, String privateKeyPem, String appSlug, String clientId, String clientSecret) {
        this.snapshot = new Snapshot(appId, privateKeyPem, appSlug, clientId, clientSecret);
    }
}
