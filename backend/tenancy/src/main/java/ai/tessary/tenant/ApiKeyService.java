// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Tokens are {@code tsy_<w|q|a>_<22 random>}; the scope letter is a human hint, the DB column is authoritative. The
 * lookup prefix includes 8 random chars (~2^48), so an attacker can't grind live prefixes and confirm them by bcrypt
 * timing. Only the bcrypt hash and prefix are stored; every lifecycle action writes an {@link AuditLog} row.
 */
@Service
public class ApiKeyService {

    public static final String TOKEN_PREFIX = "tsy_"; // base; the full literal prefix is tsy_<scope-letter>_
    public static final int RANDOM_BYTES = 16; // 22 base64url chars
    public static final int PREFIX_LEN = TOKEN_PREFIX.length() + 2 + 8; // = 14
    public static final int BCRYPT_COST = 10;

    // BCrypt.withDefaults() is safe here because a token can never reach bcrypt's 72-byte cap; anything user-supplied
    // needs PasswordAuthProvider.LONG_PASSWORDS.

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

    public record Issued(ApiKey token, String plaintext) {}

    public Issued issue(String projectId, String principalId, String name) {
        return issue(projectId, principalId, name, KeyScope.ADMIN);
    }

    /** The returned plaintext is the only time the secret is recoverable. */
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

    /** The old key stops verifying immediately. Empty if the key does not exist or is already revoked. */
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
                name,
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
     * Answered from {@link VerifiedTokenCache} when recently seen. {@code last_used_at} is written at most once per
     * configured interval per key, best effort.
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

        // Read before the row read, so a revocation landing during bcrypt discards this result instead of re-caching a
        // revoked key.
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

    public boolean revoke(String tokenId) {
        return revoke(tokenId, null);
    }

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
