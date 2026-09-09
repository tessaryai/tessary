// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Issues, verifies, rotates, and revokes managed project API keys. A key carries a
 * {@link KeyScope} family (write / query / admin).
 *
 * <p>Token format: {@code tsy_<scope-letter>_<22-char-url-safe-random>}, where the scope letter is
 * {@code w}/{@code q}/{@code a} (write/query/admin) — a human-readable scope signal so a write-only key is
 * distinguishable at a glance (the DB {@code scope} column stays authoritative). The first
 * {@value #PREFIX_LEN} chars of the full token (the 6-char {@code tsy_<letter>_} literal plus 8 random
 * chars) become the lookup prefix stored plain; bcrypt of the full token is stored for verification.</p>
 *
 * <p>The 8 random chars in the prefix give ~2^48 of search space at the
 * lookup-by-prefix layer, so an attacker can't grind a small alphabet to
 * find live prefixes and then use bcrypt-verify timing to confirm hits.</p>
 *
 * <p>Every lifecycle action (create / rotate / revoke) writes one {@link AuditLog} row so key
 * management is auditable. The raw secret is never stored — only its bcrypt hash and lookup prefix.</p>
 */
@Service
public class ApiKeyService {

    public static final String TOKEN_PREFIX = "tsy_"; // base; the full literal prefix is tsy_<scope-letter>_
    public static final int RANDOM_BYTES = 16; // 22 base64url chars
    // Literal prefix is 6 chars ("tsy_" + scope letter + "_"), constant across scopes, + 8 random chars
    // for the lookup prefix; the full token is that literal prefix plus 22 random chars.
    public static final int PREFIX_LEN = TOKEN_PREFIX.length() + 2 + 8; // = 14
    public static final int BCRYPT_COST = 10;

    // BCrypt.withDefaults() below, NOT the SHA-512 long-password strategy PasswordAuthProvider pins:
    // bcrypt reads at most 72 bytes and the default strategy throws past that, but a token here is
    // always the 6-char literal prefix plus 22 random chars, so it cannot reach the cap. The rule for
    // a future call site is the length of what gets hashed, not which of these two it sat next to —
    // anything user-supplied needs PasswordAuthProvider.LONG_PASSWORDS.

    /** The 6-char literal prefix for a scope: {@code tsy_w_} / {@code tsy_q_} / {@code tsy_a_}. */
    private static String literalPrefix(KeyScope scope) {
        return TOKEN_PREFIX + scope.letter() + "_";
    }

    private final ApiKeyRepository tokens;
    private final AuditLogRepository audit;
    private final VerifiedTokenCache cache;
    private final SecureRandom rng = new SecureRandom();

    public ApiKeyService(ApiKeyRepository tokens, AuditLogRepository audit, VerifiedTokenCache cache) {
        this.tokens = tokens;
        this.audit = audit;
        this.cache = cache;
    }

    /** Result of an issue() call: row + the plaintext token (shown to user once). */
    public record Issued(ApiKey token, String plaintext) {}

    /**
     * Issue an admin-scoped (broadest family) key — the MCP personal-token / plugin device-link mint
     * shape; equivalent to {@code issue(projectId, userId, name, KeyScope.ADMIN)}.
     */
    public Issued issue(String projectId, String principalId, String name) {
        return issue(projectId, principalId, name, KeyScope.ADMIN);
    }

    /**
     * Issue a managed key of the given family and write a {@code created} audit row. The returned
     * plaintext is the only time the secret is recoverable.
     */
    public Issued issue(String projectId, String principalId, String name, KeyScope scope) {
        byte[] random = new byte[RANDOM_BYTES];
        rng.nextBytes(random);
        String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String plaintext = literalPrefix(scope) + suffix;
        String lookupPrefix = plaintext.substring(0, Math.min(PREFIX_LEN, plaintext.length()));
        String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, plaintext.toCharArray());

        ApiKey row = ApiKey.of(
                Ids.ulid(),
                projectId,
                principalId,
                name == null || name.isBlank() ? "(unnamed)" : name,
                lookupPrefix,
                hash,
                Instant.now().toString(),
                null,
                null,
                scope.wire());
        tokens.insert(row);
        recordAudit(projectId, row.id(), principalId, AuditLog.Action.CREATED, null);
        return new Issued(row, plaintext);
    }

    /**
     * Rotate a key: revoke the old row and issue a fresh secret with the <em>same</em> name and
     * scope. Writes a {@code revoked} audit row for the old key and a {@code rotated} audit row
     * for the new key (noting the old key id). The old key stops verifying immediately. Returns empty if
     * the key does not exist or is already revoked.
     */
    public Optional<Issued> rotate(String tokenId, String actorUserId) {
        Optional<ApiKey> existing = tokens.findById(tokenId);
        if (existing.isEmpty() || existing.get().isRevoked()) return Optional.empty();
        ApiKey old = existing.get();
        tokens.revoke(old.id(), Instant.now().toString());
        cache.invalidate(old.id());
        recordAudit(old.projectId(), old.id(), actorUserId, AuditLog.Action.REVOKED, "rotated");
        Issued fresh = issueWithoutAudit(old.projectId(), actorUserId, old.name(), old.scopeEnum());
        recordAudit(old.projectId(), fresh.token().id(), actorUserId, AuditLog.Action.ROTATED, "from=" + old.id());
        return Optional.of(fresh);
    }

    private Issued issueWithoutAudit(String projectId, String principalId, String name, KeyScope scope) {
        byte[] random = new byte[RANDOM_BYTES];
        rng.nextBytes(random);
        String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String plaintext = literalPrefix(scope) + suffix;
        String lookupPrefix = plaintext.substring(0, Math.min(PREFIX_LEN, plaintext.length()));
        String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, plaintext.toCharArray());
        ApiKey row = ApiKey.of(
                Ids.ulid(),
                projectId,
                principalId,
                name == null || name.isBlank() ? "(unnamed)" : name,
                lookupPrefix,
                hash,
                Instant.now().toString(),
                null,
                null,
                scope.wire());
        tokens.insert(row);
        return new Issued(row, plaintext);
    }

    /**
     * Validate a presented bearer token. Returns the matching {@link ApiKey} when
     * the token exists, is unrevoked, and the bcrypt verification succeeds. We update
     * {@code last_used_at} on success (best effort; failure to update is logged but
     * does not reject the auth).
     *
     * <p>A recently-seen token is answered from {@link VerifiedTokenCache} without a query or a bcrypt.
     * That is the whole point of the cache — bcrypt at cost 10 is deliberately expensive and the answer
     * for a machine credential does not change between requests — and its bounds, its separation of
     * verified from rejected tokens, and the reason a revocation does not wait for a TTL are all
     * documented on that class. {@code last_used_at} is written at most once per
     * {@code evals.auth.token-cache.last-used-write-interval-seconds} per key rather than per request.
     */
    public Optional<ApiKey> verify(String presented) {
        if (presented == null || !presented.startsWith(TOKEN_PREFIX)) return Optional.empty();
        if (presented.length() < PREFIX_LEN) return Optional.empty();

        switch (cache.lookup(presented)) {
            case VerifiedTokenCache.Lookup.Verified v -> {
                if (v.writeLastUsed()) markUsed(v.key());
                return Optional.of(v.key());
            }
            case VerifiedTokenCache.Lookup.Rejected ignored -> {
                return Optional.empty();
            }
            case VerifiedTokenCache.Lookup.Unknown ignored -> {
                /* fall through to the real verification */
            }
        }

        // Read BEFORE the row read, so an invalidation that lands while bcrypt runs below is detected
        // and this result is discarded instead of re-caching a key that was revoked mid-verification.
        long observedGeneration = cache.generation();
        String prefix = presented.substring(0, PREFIX_LEN);
        Optional<ApiKey> row = tokens.findByPrefix(prefix);
        if (row.isEmpty() || row.get().isRevoked()) {
            cache.rememberRejected(presented);
            return Optional.empty();
        }
        BCrypt.Result result =
                BCrypt.verifyer().verify(presented.toCharArray(), row.get().tokenHash());
        if (!result.verified) {
            cache.rememberRejected(presented);
            return Optional.empty();
        }
        cache.rememberVerified(presented, row.get(), observedGeneration);
        markUsed(row.get());
        return row;
    }

    private void markUsed(ApiKey key) {
        try {
            tokens.markLastUsed(key.id(), Instant.now().toString());
        } catch (RuntimeException ignored) {
            /* best effort */
        }
    }

    /** Revoke a key (idempotent) and, when it was live, write a {@code revoked} audit row. */
    public boolean revoke(String tokenId) {
        return revoke(tokenId, null);
    }

    /** Revoke a key and attribute the action to {@code actorUserId} in the audit trail. */
    public boolean revoke(String tokenId, @Nullable String actorUserId) {
        Optional<ApiKey> existing = tokens.findById(tokenId);
        boolean revoked = tokens.revoke(tokenId, Instant.now().toString());
        cache.invalidate(tokenId);
        if (revoked && existing.isPresent()) {
            recordAudit(existing.get().projectId(), tokenId, actorUserId, AuditLog.Action.REVOKED, null);
        }
        return revoked;
    }

    private void recordAudit(
            String projectId,
            @Nullable String apiKeyId,
            @Nullable String actorUserId,
            AuditLog.Action action,
            @Nullable String details) {
        try {
            audit.insert(AuditLog.forApiKey(
                    Ids.ulid(),
                    projectId,
                    apiKeyId,
                    actorUserId,
                    action.wire(),
                    details,
                    Instant.now().toString()));
        } catch (RuntimeException ignored) {
            /* audit is best-effort; never fail the lifecycle action on a log write */
        }
    }
}
