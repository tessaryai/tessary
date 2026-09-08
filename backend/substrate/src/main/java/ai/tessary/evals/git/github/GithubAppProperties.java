// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "evals.git.github")
public class GithubAppProperties {

    /** Default GitHub REST API host (github.com SaaS); GHES installs override per-integration. */
    public static final String DEFAULT_API_HOST = "api.github.com";

    /**
     * The six credential fields as one immutable snapshot, held behind a single {@code volatile}
     * reference so a swap is both visible and atomic across all six values at once. Spring's binder
     * calls the individual setters below one property at a time at startup (single-threaded, before
     * any reader exists, so that is safe); the runtime live-update path in {@link
     * GithubAppConfigService} instead calls {@link #applyAll} once with all six values, so a
     * concurrent unsynchronized reader (e.g. {@code GithubTokenService.authHeader()} or {@code
     * SignalResolver.isConfigured()}, neither of which coordinates with the writer) always observes
     * either the fully-old or the fully-new set of values — never a mix, and never a stale value
     * pinned forever by a missing happens-before edge.
     */
    private record Snapshot(
            String appId,
            String privateKeyPem,
            String webhookSecret,
            String appSlug,
            String clientId,
            String clientSecret) {
        static final Snapshot EMPTY = new Snapshot("", "", "", "", "", "");
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public String getAppId() {
        return snapshot.appId();
    }

    public void setAppId(String appId) {
        Snapshot s = snapshot;
        snapshot =
                new Snapshot(appId, s.privateKeyPem(), s.webhookSecret(), s.appSlug(), s.clientId(), s.clientSecret());
    }

    public String getClientId() {
        return snapshot.clientId();
    }

    public void setClientId(String clientId) {
        Snapshot s = snapshot;
        snapshot =
                new Snapshot(s.appId(), s.privateKeyPem(), s.webhookSecret(), s.appSlug(), clientId, s.clientSecret());
    }

    public String getClientSecret() {
        return snapshot.clientSecret();
    }

    public void setClientSecret(String clientSecret) {
        Snapshot s = snapshot;
        snapshot =
                new Snapshot(s.appId(), s.privateKeyPem(), s.webhookSecret(), s.appSlug(), s.clientId(), clientSecret);
    }

    /** True when OAuth client credentials are present (required to verify the installer's identity). */
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
        snapshot =
                new Snapshot(s.appId(), s.privateKeyPem(), s.webhookSecret(), appSlug, s.clientId(), s.clientSecret());
    }

    public String getPrivateKeyPem() {
        return snapshot.privateKeyPem();
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        Snapshot s = snapshot;
        snapshot =
                new Snapshot(s.appId(), privateKeyPem, s.webhookSecret(), s.appSlug(), s.clientId(), s.clientSecret());
    }

    public String getWebhookSecret() {
        return snapshot.webhookSecret();
    }

    public void setWebhookSecret(String webhookSecret) {
        Snapshot s = snapshot;
        snapshot =
                new Snapshot(s.appId(), s.privateKeyPem(), webhookSecret, s.appSlug(), s.clientId(), s.clientSecret());
    }

    public boolean isConfigured() {
        Snapshot s = snapshot;
        return s.appId() != null
                && !s.appId().isBlank()
                && s.privateKeyPem() != null
                && !s.privateKeyPem().isBlank();
    }

    /**
     * Atomically replace all six fields in one visible swap — used by {@link GithubAppConfigService}
     * on the live-update path (manifest wizard capture, and boot-time load from a stored DB row) so
     * a concurrent reader can never observe a torn mix of old and new field values. See {@link
     * Snapshot}'s note for why this differs from the individual setters above.
     */
    void applyAll(
            String appId,
            String privateKeyPem,
            String webhookSecret,
            String appSlug,
            String clientId,
            String clientSecret) {
        this.snapshot = new Snapshot(appId, privateKeyPem, webhookSecret, appSlug, clientId, clientSecret);
    }
}
