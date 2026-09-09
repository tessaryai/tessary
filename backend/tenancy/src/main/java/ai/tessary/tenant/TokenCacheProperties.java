// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounds for {@link VerifiedTokenCache}, the short-lived memory of which bearer tokens have already
 * been through bcrypt.
 *
 * <p>Lives in {@code tenant} rather than beside {@code auth/AuthProperties} so that {@code tenant} does
 * not have to import from {@code auth}: {@code auth} already depends on this package
 * ({@code BearerTokenAuthenticator} → {@link ApiKeyService}), and pointing the dependency back the other
 * way would make the two packages mutually recursive to save one class. The key name is still
 * {@code tessary.auth.token-cache.*}, because that is where an operator will look for it.
 *
 * <p>Every default here is a bound on someone else's behaviour, so each one is chosen against the abuse
 * it exists to stop rather than for round-numberedness. See {@link VerifiedTokenCache} for the threat
 * model the pair of caps implements.
 */
@Component
@ConfigurationProperties(prefix = "tessary.auth.token-cache")
public class TokenCacheProperties {

    /**
     * Ops kill switch. False restores the pre-cache behaviour exactly — one {@code findByPrefix}, one
     * bcrypt and one {@code last_used_at} write on every authenticated request — which is the state to
     * fall back to if this cache is ever suspected in an auth incident.
     */
    private boolean enabled = true;

    /**
     * How long a verified token stays verified, in seconds.
     *
     * <p><b>This is not the revocation window.</b> Every revocation path evicts as it revokes — see
     * {@link VerifiedTokenCache} for which paths those are and why each one has to. The TTL bounds only
     * what reaches the database without going through them: a row edited by hand, a restore from backup,
     * another replica. Sixty seconds keeps that shorter than the time it takes to notice a leaked key,
     * while still removing bcrypt from all but one request a minute per token.
     */
    private long ttlSeconds = 60;

    /**
     * Most verified tokens held at once. Reached only by an install with more live tokens than this;
     * past it the least recently used entry is evicted, never the newest, and an evicted token simply
     * pays bcrypt again on its next request.
     */
    private int maxEntries = 4096;

    /**
     * How long a rejected token is remembered, in seconds. Short, because a rejection is a guess about
     * the future: the row it was rejected against can be issued, restored or un-revoked between now and
     * then, and a stale rejection would lock out a token that has become valid.
     */
    private long negativeTtlSeconds = 10;

    /**
     * Most rejected tokens held at once, capped separately from {@link #maxEntries} on purpose; the
     * reason is in {@link VerifiedTokenCache}'s class documentation.
     */
    private int maxRejections = 1024;

    /**
     * Minimum interval between {@code last_used_at} writes for one key, in seconds. Zero writes on
     * every request, which is the old behaviour.
     *
     * <p>The column is a "when was this key last seen" signal for key management, not an audit record —
     * the audit trail is {@code audit_log} and is unaffected. Writing it per request made every push
     * from one exporter an {@code UPDATE} of the same single row, which serialises concurrent requests
     * on that row's lock and produces a dead tuple each time; a measurement run of this ingest path
     * logged 61,845 updates against one live row. At sixty seconds the column is accurate to the minute
     * and costs one write per key per minute.
     */
    private long lastUsedWriteIntervalSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long v) {
        this.ttlSeconds = v;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int v) {
        if (v < 1) {
            throw new IllegalArgumentException("tessary.auth.token-cache.max-entries must be at least 1, not " + v);
        }
        this.maxEntries = v;
    }

    public long getNegativeTtlSeconds() {
        return negativeTtlSeconds;
    }

    public void setNegativeTtlSeconds(long v) {
        this.negativeTtlSeconds = v;
    }

    public int getMaxRejections() {
        return maxRejections;
    }

    public void setMaxRejections(int v) {
        if (v < 1) {
            throw new IllegalArgumentException("tessary.auth.token-cache.max-rejections must be at least 1, not " + v);
        }
        this.maxRejections = v;
    }

    public long getLastUsedWriteIntervalSeconds() {
        return lastUsedWriteIntervalSeconds;
    }

    public void setLastUsedWriteIntervalSeconds(long v) {
        this.lastUsedWriteIntervalSeconds = v;
    }
}
