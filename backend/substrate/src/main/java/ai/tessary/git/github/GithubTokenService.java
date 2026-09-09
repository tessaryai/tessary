// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProvider;
import ai.tessary.git.GitTokenService;
import ai.tessary.ingest.BoundedBody;
import ai.tessary.ingest.UrlGuard;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GithubTokenService implements GitTokenService {

    private static final Logger log = LoggerFactory.getLogger(GithubTokenService.class);

    private static final String LABEL = "github";
    private static final long REFRESH_MARGIN_SECONDS = 60;
    // GitHub list endpoints cap at 100/page; cap pages so a runaway cursor can't loop forever.
    private static final int MAX_PAGES = 50;

    private final GithubAppProperties props;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final HttpClient http;
    // Keyed by integration id (NOT installation id): a cache hit must not require decrypting the
    // sealed credentials to compute the key — that AES-GCM open ran on every API call before.
    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();
    // Per-integration mint lock: collapse concurrent cache-miss mints (e.g. a startup burst) into
    // one GitHub token request instead of a thundering herd.
    private final ConcurrentMap<String, Object> mintLocks = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {}

    public GithubTokenService(GithubAppProperties props, SecretBox secretBox, ObjectMapper mapper) {
        this.props = props;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public GitProvider provider() {
        return GitProvider.GITHUB;
    }

    @Override
    public String authHeader(GitIntegrationRow integ) {
        // Cache check stays FIRST, ahead of the PAT lookup: a cache hit must not require decrypting
        // the sealed credentials (see the cache field's own comment above) and a PAT integration
        // never populates this cache (below), so an App-mode cache hit costs nothing extra here —
        // it still returns before any AES-GCM open.
        String key = integ.id();
        CachedToken cached = cache.get(key);
        if (isFresh(cached)) {
            return "Bearer " + cached.token();
        }
        // PAT fallback comes BEFORE the App-configured gate: a PAT-mode integration has no App to
        // be configured, and gating on isConfigured() up front would reject it even when the sealed
        // credentials carry a perfectly usable token. That gate used to be the first line
        // unconditionally, which is exactly the bug PAT mode exists to fix.
        Optional<String> pat = patToken(integ);
        if (pat.isPresent()) {
            // Deliberately no cache entry: a PAT has no expiry to refresh against, so the only way
            // to honor revocation promptly is to decrypt on every call rather than serve a
            // cached-forever token that would keep authenticating after the self-hoster revokes it.
            return "Bearer " + pat.get();
        }
        if (!props.isConfigured()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, LABEL);
        }
        // Miss/expired: serialize mints for this integration so a burst doesn't fire N token requests.
        // The token-mint HTTP call runs INSIDE this monitor on purpose — that's what collapses a
        // thundering herd into a single mint. On JDK 25 (JEP 491) a virtual thread blocking inside a
        // synchronized block does not pin its carrier, so holding the HTTP call here is Loom-safe.
        Object lock = mintLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            CachedToken recheck = cache.get(key);
            if (isFresh(recheck)) {
                return "Bearer " + recheck.token();
            }
            long installationId = installationId(integ); // AES-GCM open happens only on a real miss
            CachedToken minted = mintInstallationToken(integ, installationId);
            cache.put(key, minted);
            return "Bearer " + minted.token();
        }
    }

    private static boolean isFresh(CachedToken t) {
        return t != null && Instant.now().isBefore(t.expiresAt().minusSeconds(REFRESH_MARGIN_SECONDS));
    }

    private long installationId(GitIntegrationRow integ) {
        String json;
        try {
            json = secretBox.open(integ.credentialsEnc());
        } catch (RuntimeException e) {
            throw new TessaryException(GitError.INSTALLATION_NOT_FOUND, e, "(sealed)");
        }
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode idNode = node.get("installationId");
            if (idNode == null || !idNode.canConvertToLong()) {
                throw new TessaryException(GitError.INSTALLATION_NOT_FOUND, "(missing)");
            }
            return idNode.asLong();
        } catch (TessaryException e) {
            throw e;
        } catch (Exception e) {
            throw new TessaryException(GitError.INSTALLATION_NOT_FOUND, e, "(unparseable)");
        }
    }

    /**
     * A sealed personal-access-token, if this integration was connected in PAT mode instead of
     * through the App. Mirrors {@link #installationId}'s decrypt-and-look-up-a-field shape, but
     * returns empty rather than throwing when the field is absent — the caller falls through to
     * the App-token path in that case, so "no PAT" is not an error here.
     */
    private Optional<String> patToken(GitIntegrationRow integ) {
        if (integ.credentialsEnc() == null || integ.credentialsEnc().isBlank()) {
            return Optional.empty();
        }
        String json;
        try {
            json = secretBox.open(integ.credentialsEnc());
        } catch (RuntimeException e) {
            // A sealed blob that fails to open here is the same integrity failure installationId()
            // surfaces below, just reached from the PAT-first path — not "no PAT", so it still throws.
            throw new TessaryException(GitError.INSTALLATION_NOT_FOUND, e, "(sealed)");
        }
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode tokenNode = node.get("token");
            if (tokenNode == null
                    || tokenNode.asText(null) == null
                    || tokenNode.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(tokenNode.asText());
        } catch (Exception e) {
            throw new TessaryException(GitError.INSTALLATION_NOT_FOUND, e, "(unparseable)");
        }
    }

    /** One installed repo the App grants access to (used by the post-install callback). */
    public record InstalledRepo(String owner, String name, String defaultBranch) {}

    /**
     * Mint a raw installation token from a bare installation id (no integration row yet) —
     * for the post-install callback, before the project is bound. Throws if the App isn't
     * configured or the mint is rejected.
     */
    public String mintInstallationToken(long installationId, String host) {
        if (!props.isConfigured()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, LABEL);
        }
        return mintInstallationToken(normHost(host), installationId).token();
    }

    /**
     * List the repositories an installation grants access to ({@code GET
     * /installation/repositories}). Used by the callback to resolve owner/name post-install.
     */
    public java.util.List<InstalledRepo> listInstallationRepos(long installationId, String host) {
        String token = mintInstallationToken(installationId, host);
        String h = normHost(host);
        java.util.List<InstalledRepo> out = new java.util.ArrayList<>();
        for (JsonNode r :
                fetchAllPages("https://" + h + "/installation/repositories?per_page=100", token, "repositories")) {
            String owner = r.path("owner").path("login").asText(null);
            String name = r.path("name").asText(null);
            String branch = r.path("default_branch").asText("main");
            if (owner != null && name != null) out.add(new InstalledRepo(owner, name, branch));
        }
        return out;
    }

    /**
     * Exchange the OAuth {@code code} GitHub returns from "authorize user during installation" for
     * the installer's USER access token. Proves who completed the install (used to verify they
     * actually administer the installation). github.com only.
     */
    public String exchangeUserCode(String code) {
        if (!props.hasOAuth()) {
            throw new TessaryException(GitError.INSTALL_NOT_VERIFIED);
        }
        String form = "client_id=" + enc(props.getClientId())
                + "&client_secret=" + enc(props.getClientSecret())
                + "&code=" + enc(code);
        URI uri = UrlGuard.requirePublicHttp("https://github.com/login/oauth/access_token");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            HttpResponse<String> res = http.send(req, BoundedBody.string());
            if (res.statusCode() / 100 != 2) {
                throw new TessaryException(GitError.TOKEN_MINT_FAILED, LABEL);
            }
            JsonNode node = mapper.readTree(res.body());
            String token = node.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                // GitHub returns 200 with {"error":...} on a bad/expired code.
                throw new TessaryException(GitError.INSTALL_NOT_VERIFIED);
            }
            return token;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
        }
    }

    /** The installation ids the holder of {@code userToken} can administer ({@code GET /user/installations}). */
    public Set<Long> listUserInstallations(String userToken) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode n :
                fetchAllPages("https://api.github.com/user/installations?per_page=100", userToken, "installations")) {
            JsonNode id = n.path("id");
            if (id.canConvertToLong()) ids.add(id.asLong());
        }
        return Set.copyOf(ids);
    }

    /**
     * Fetch every page of a GitHub list endpoint, following the {@code Link} rel="next" cursor, and
     * flatten the named array field from each page. Without this an installation granting &gt;100 repos
     * (or a user administering &gt;100 installations) is silently truncated to the first page — which
     * would hide repos from the connect picker and could reject a legitimately-administered install.
     */
    private java.util.List<JsonNode> fetchAllPages(String firstUrl, String bearer, String arrayField) {
        java.util.List<JsonNode> out = new java.util.ArrayList<>();
        String next = firstUrl;
        for (int page = 0; next != null && page < MAX_PAGES; page++) {
            URI uri = UrlGuard.requirePublicHttp(next);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("Authorization", "Bearer " + bearer)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> res;
            try {
                res = http.send(req, BoundedBody.string());
            } catch (java.io.IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
            }
            if (res.statusCode() / 100 != 2) {
                throw new TessaryException(GitError.TOKEN_MINT_FAILED, LABEL);
            }
            try {
                JsonNode arr = mapper.readTree(res.body()).path(arrayField);
                if (arr.isArray()) arr.forEach(out::add);
            } catch (TessaryException e) {
                throw e;
            } catch (Exception e) {
                throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
            }
            next = nextLink(res.headers().firstValue("Link").orElse(null));
        }
        // A still-present cursor at the cap means we silently truncated (>MAX_PAGES*100 items) — make a
        // user with >5000 repos/installations diagnosable. Categorical only (field name + cap), no URL.
        if (next != null) {
            log.warn("github pagination capped field={} maxPages={}", arrayField, MAX_PAGES);
        }
        return out;
    }

    /** Extract the rel="next" URL from a GitHub {@code Link} response header, or null when absent. */
    static String nextLink(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) return null;
        // e.g. <https://api.github.com/...&page=2>; rel="next", <...&page=9>; rel="last"
        for (String part : linkHeader.split(",")) {
            String seg = part.trim();
            int lt = seg.indexOf('<');
            int gt = seg.indexOf('>', lt + 1);
            // Match the full "; rel="next"" param per RFC 5988 so a server emitting rel="next-page"
            // doesn't false-match on the bare substring.
            if (lt >= 0 && gt > lt && seg.substring(gt + 1).contains("; rel=\"next\"")) {
                return seg.substring(lt + 1, gt);
            }
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String normHost(String host) {
        return (host == null || host.isBlank()) ? "api.github.com" : host;
    }

    private CachedToken mintInstallationToken(GitIntegrationRow integ, long installationId) {
        return mintInstallationToken(normHost(integ.host()), installationId);
    }

    private CachedToken mintInstallationToken(String host, long installationId) {
        String jwt = appJwt();
        URI uri = UrlGuard.requirePublicHttp(
                "https://" + host + "/app/installations/" + installationId + "/access_tokens");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, BoundedBody.string());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("github token mint failed installationId={}", installationId);
            throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
        }
        if (res.statusCode() / 100 != 2) {
            log.warn("github token mint rejected installationId={} status={}", installationId, res.statusCode());
            throw new TessaryException(GitError.TOKEN_MINT_FAILED, LABEL);
        }
        try {
            JsonNode node = mapper.readTree(res.body());
            String token = node.path("token").asText(null);
            String expiresAt = node.path("expires_at").asText(null);
            if (token == null || token.isBlank() || expiresAt == null || expiresAt.isBlank()) {
                throw new TessaryException(GitError.TOKEN_MINT_FAILED, LABEL);
            }
            return new CachedToken(token, Instant.parse(expiresAt));
        } catch (TessaryException e) {
            throw e;
        } catch (Exception e) {
            throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
        }
    }

    private String appJwt() {
        Instant now = Instant.now();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iat\":" + (now.getEpochSecond() - 60)
                + ",\"exp\":" + (now.getEpochSecond() + 540)
                + ",\"iss\":\"" + props.getAppId() + "\"}";
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String signingInput = url.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + url.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            PrivateKey key = parsePrivateKey(props.getPrivateKeyPem());
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String signature = url.encodeToString(sig.sign());
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
        }
    }

    /**
     * Parse an RSA private key from PEM. GitHub App keys download in PKCS#1
     * ({@code -----BEGIN RSA PRIVATE KEY-----}); we also accept PKCS#8
     * ({@code -----BEGIN PRIVATE KEY-----}). A PKCS#1 body is wrapped into a
     * PKCS#8 {@code PrivateKeyInfo} first, so a single {@link KeyFactory} path
     * handles both — no third-party crypto provider needed.
     */
    static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem.replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        if (pkcs1) der = pkcs1ToPkcs8(der);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * Wrap a PKCS#1 {@code RSAPrivateKey} DER inside a PKCS#8 {@code PrivateKeyInfo}:
     * {@code SEQUENCE { version 0, AlgorithmIdentifier(rsaEncryption, NULL), OCTET STRING(pkcs1) }}.
     */
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        // AlgorithmIdentifier: SEQUENCE { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }.
        byte[] algId = {
            0x30,
            0x0d,
            0x06,
            0x09,
            0x2a,
            (byte) 0x86,
            0x48,
            (byte) 0x86,
            (byte) 0xf7,
            0x0d,
            0x01,
            0x01,
            0x01,
            0x05,
            0x00
        };
        byte[] privateKey = derTlv((byte) 0x04, pkcs1); // OCTET STRING
        return derTlv((byte) 0x30, concat(version, algId, privateKey)); // SEQUENCE
    }

    /** Emit a DER tag-length-value, using definite long-form length when needed. */
    private static byte[] derTlv(byte tag, byte[] content) {
        byte[] len = derLength(content.length);
        byte[] out = new byte[1 + len.length + content.length];
        out[0] = tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(content, 0, out, 1 + len.length, content.length);
        return out;
    }

    /** DER definite length: short form below 128, else long form (0x80|n then big-endian bytes). */
    private static byte[] derLength(int len) {
        if (len < 0x80) return new byte[] {(byte) len};
        int n = 0;
        for (int t = len; t > 0; t >>= 8) n++;
        byte[] out = new byte[1 + n];
        out[0] = (byte) (0x80 | n);
        for (int i = 0; i < n; i++) out[out.length - 1 - i] = (byte) (len >> (8 * i));
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
