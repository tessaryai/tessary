// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth.link;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import at.favre.lib.crypto.bcrypt.BCrypt;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Device-authorization (RFC 8628 style) link flow. The plugin starts a link,
 * shows the user a short {@code user_code} + verification URL, and polls with
 * the secret {@code device_code}. A signed-in browser confirms the code against
 * a project; the next poll mints a project-scoped API key and returns it once.
 *
 * <p>Security: the secret {@code device_code} is high-entropy, bcrypt-hashed,
 * and never displayed — it is the only credential that can be polled into a
 * token. The short {@code user_code} is only usable inside the authenticated
 * browser confirm, so a shoulder-surfed user_code grants nothing. The API key
 * is minted lazily at claim time, so a plaintext token never persists.
 */
@Service
public class DeviceLinkService {

    private static final Logger log = LoggerFactory.getLogger(DeviceLinkService.class);

    static final Duration TTL = Duration.ofMinutes(10);
    static final int POLL_INTERVAL_SECONDS = 3;
    private static final int DEVICE_CODE_BYTES = 24;
    private static final String DEVICE_PREFIX = "link_";
    // literal "link_" + 8 random chars; full code has more entropy after that.
    private static final int PREFIX_LEN = DEVICE_PREFIX.length() + 8;
    private static final int BCRYPT_COST = 10;
    private static final char[] USER_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789".toCharArray();

    private final DeviceLinkRepository links;
    private final ApiKeyService mcpTokens;
    private final ProjectRepository projects;
    private final OrganizationRepository orgs;
    private final SecureRandom rng = new SecureRandom();

    public DeviceLinkService(
            DeviceLinkRepository links,
            ApiKeyService mcpTokens,
            ProjectRepository projects,
            OrganizationRepository orgs) {
        this.links = links;
        this.mcpTokens = mcpTokens;
        this.projects = projects;
        this.orgs = orgs;
    }

    public record Started(String deviceCode, String userCode, Instant expiresAt) {}

    /** Status the browser confirm screen reads. */
    public record View(String userCode, @Nullable String clientLabel, String status, String expiresAt) {}

    /** Result of a poll. {@code token}/slugs are only set when status == "ready". */
    public record PollOutcome(
            String status,
            @Nullable String token,
            @Nullable String orgSlug,
            @Nullable String projectSlug) {
        static PollOutcome of(String status) {
            return new PollOutcome(status, null, null, null);
        }
    }

    public Started start(@Nullable String clientLabel) {
        byte[] random = new byte[DEVICE_CODE_BYTES];
        rng.nextBytes(random);
        String deviceCode =
                DEVICE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String prefix = deviceCode.substring(0, Math.min(PREFIX_LEN, deviceCode.length()));
        String hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, deviceCode.toCharArray());
        String now = Instant.now().toString();
        String expires = Instant.now().plus(TTL).toString();

        // user_code uniqueness is enforced by the DB; retry on the rare collision.
        for (int attempt = 0; attempt < 5; attempt++) {
            String userCode = generateUserCode();
            DeviceLink row = new DeviceLink(
                    Ids.ulid(),
                    prefix,
                    hash,
                    userCode,
                    DeviceLink.PENDING,
                    clientLabel,
                    null,
                    null,
                    null,
                    null,
                    now,
                    expires,
                    null,
                    0);
            try {
                links.insert(row);
                return new Started(deviceCode, userCode, Instant.parse(expires));
            } catch (RuntimeException e) {
                log.debug("device link user_code collision, retrying: {}", e.getMessage());
            }
        }
        throw new TessaryException(CommonError.INTERNAL);
    }

    /** Browser-side: metadata for the confirm screen. Empty when unknown/expired. */
    public Optional<View> view(String userCode) {
        return links.findByUserCode(userCode)
                .map(l -> new View(l.userCode(), l.clientLabel(), effectiveStatus(l), l.expiresAt()));
    }

    /** Browser-side: bind a confirmed link to a project the user belongs to (membership checked by caller). */
    public boolean confirm(String userCode, String orgId, String projectId, String userId) {
        DeviceLink l = links.findByUserCode(userCode).orElse(null);
        if (l == null || isExpired(l)) return false;
        // Conditional UPDATE (pending → confirmed) — the row check is atomic, so two
        // concurrent confirms can't both win.
        return links.markConfirmed(l.id(), orgId, projectId, userId) == 1;
    }

    public boolean deny(String userCode) {
        DeviceLink l = links.findByUserCode(userCode).orElse(null);
        return l != null && links.transitionIf(l.id(), DeviceLink.PENDING, DeviceLink.DENIED) == 1;
    }

    /** Plugin-side: exchange the secret device code for status (and, when confirmed, a freshly minted token). */
    public PollOutcome poll(@Nullable String deviceCode) {
        if (deviceCode == null || !deviceCode.startsWith(DEVICE_PREFIX) || deviceCode.length() < PREFIX_LEN) {
            return PollOutcome.of("expired");
        }
        DeviceLink l = links.findByPrefix(deviceCode.substring(0, PREFIX_LEN)).orElse(null);
        if (l == null) return PollOutcome.of("expired");
        if (!BCrypt.verifyer().verify(deviceCode.toCharArray(), l.deviceCodeHash()).verified) {
            return PollOutcome.of("expired");
        }
        if (DeviceLink.CLAIMED.equals(l.status())) return PollOutcome.of("already_claimed");
        if (DeviceLink.DENIED.equals(l.status())) return PollOutcome.of("denied");
        if (isExpired(l)) {
            if (!DeviceLink.EXPIRED.equals(l.status())) links.markStatus(l.id(), DeviceLink.EXPIRED);
            return PollOutcome.of("expired");
        }

        // Throttle: enforce the advertised poll interval per code.
        if (l.lastPolledAt() != null) {
            try {
                if (Instant.parse(l.lastPolledAt()).isAfter(Instant.now().minusSeconds(POLL_INTERVAL_SECONDS))) {
                    return PollOutcome.of("slow_down");
                }
            } catch (Exception ignored) {
                /* fall through */
            }
        }
        links.recordPoll(l.id(), Instant.now().toString(), l.pollCount() + 1);

        if (DeviceLink.PENDING.equals(l.status())) return PollOutcome.of("authorization_pending");

        // Confirmed: claim atomically first so a concurrent poll can't also mint.
        // Only the poll whose UPDATE flips confirmed → claimed proceeds to issue a
        // token; the loser sees the row already claimed.
        if (links.beginClaim(l.id()) != 1) {
            return PollOutcome.of("already_claimed");
        }
        Project project = projects.findById(l.projectId()).orElse(null);
        Organization org = orgs.findById(l.orgId()).orElse(null);
        if (project == null || org == null) {
            links.markStatus(l.id(), DeviceLink.EXPIRED);
            return PollOutcome.of("expired");
        }
        // Lazy-mint the token now (plaintext never persists) and attach it to the claimed row.
        ApiKeyService.Issued issued = mcpTokens.issue(l.projectId(), l.userId(), "Claude Code link " + l.userCode());
        links.setToken(l.id(), issued.token().id());
        return new PollOutcome("ready", issued.plaintext(), org.slug(), project.slug());
    }

    private String effectiveStatus(DeviceLink l) {
        return isExpired(l) && !DeviceLink.CLAIMED.equals(l.status()) ? DeviceLink.EXPIRED : l.status();
    }

    private static boolean isExpired(DeviceLink l) {
        try {
            return Instant.parse(l.expiresAt()).isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    private String generateUserCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(USER_CODE_ALPHABET[rng.nextInt(USER_CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
