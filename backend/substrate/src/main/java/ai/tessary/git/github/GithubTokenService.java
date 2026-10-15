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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubTokenService implements GitTokenService {

    private static final Logger log = LoggerFactory.getLogger(GithubTokenService.class);

    private static final String LABEL = "github";
    private static final long REFRESH_MARGIN_SECONDS = 60;
    // GitHub caps lists at 100 per page; cap pages so a runaway cursor can't loop.
    private static final int MAX_PAGES = 50;

    private final GithubAppProperties props;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String webOrigin;
    private final String apiOrigin;
    // Keyed by integration id, so a cache hit never needs to decrypt the sealed credentials.
    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();
    // Collapses concurrent cache-miss mints into one GitHub request.
    private final ConcurrentMap<String, Object> mintLocks = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {}

    @Autowired
    public GithubTokenService(GithubAppProperties props, SecretBox secretBox, ObjectMapper mapper) {
        this(
                props,
                secretBox,
                mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                "https://github.com",
                "https://" + GithubAppProperties.DEFAULT_API_HOST);
    }

    GithubTokenService(
            GithubAppProperties props,
            SecretBox secretBox,
            ObjectMapper mapper,
            HttpClient http,
            String webOrigin,
            String apiOrigin) {
        this.props = props;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.http = http;
        this.webOrigin = webOrigin;
        this.apiOrigin = apiOrigin;
    }

    @Override
    public GitProvider provider() {
        return GitProvider.GITHUB;
    }

    @Override
    public String authHeader(GitIntegrationRow integ) {
        // Cache check first, so an App-mode hit returns before any AES-GCM open.
        String key = integ.id();
        CachedToken cached = cache.get(key);
        if (isFresh(cached)) {
            return "Bearer " + cached.token();
        }
        // PAT fallback before the App-configured gate, which would reject a PAT integration.
        Optional<String> pat = patToken(integ);
        if (pat.isPresent()) {
            // No cache entry: a PAT has no expiry, so decrypting per call is the only way to honor revocation promptly.
            return "Bearer " + pat.get();
        }
        if (!props.isConfigured()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, LABEL);
        }
        // The mint runs inside the monitor on purpose to collapse a thundering herd. On JDK 25 (JEP 491) a virtual
        // thread blocking in synchronized does not pin its carrier.
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
        JsonNode idNode = parseCredentials(json).get("installationId");
        if (idNode == null || !idNode.canConvertToLong()) {
            throw new TessaryException(GitError.INSTALLATION_NOT_FOUND, "(missing)");
        }
        return idNode.asLong();
    }

    private JsonNode parseCredentials(String json) {
        try {
            return mapper.readTree(json);
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
            // A blob that fails to open is an integrity failure, not "no PAT", so it still throws.
            throw new TessaryException(GitError.INSTALLATION_NOT_FOUND, e, "(sealed)");
        }
        JsonNode tokenNode = parseCredentials(json).get("token");
        if (tokenNode == null
                || tokenNode.asText(null) == null
                || tokenNode.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(tokenNode.asText());
    }

    public record InstalledRepo(String owner, String name, String defaultBranch) {}

    /** For the post-install callback, before the project is bound. */
    public String mintInstallationToken(long installationId, String host) {
        if (!props.isConfigured()) {
            throw new TessaryException(GitError.MISSING_APP_CONFIG, LABEL);
        }
        return mintInstallationToken(normHost(host), installationId).token();
    }

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

    /** Proves who completed the install. github.com only. */
    public String exchangeUserCode(String code) {
        if (!props.hasOAuth()) {
            throw new TessaryException(GitError.INSTALL_NOT_VERIFIED);
        }
        String form = "client_id=" + enc(props.getClientId())
                + "&client_secret=" + enc(props.getClientSecret())
                + "&code=" + enc(code);
        URI uri = UrlGuard.requirePublicHttp(webOrigin + "/login/oauth/access_token");
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
                // GitHub returns 200 with {"error":...} on a bad or expired code.
                throw new TessaryException(GitError.INSTALL_NOT_VERIFIED);
            }
            return token;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
        }
    }

    public Set<Long> listUserInstallations(String userToken) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode n : fetchAllPages(apiOrigin + "/user/installations?per_page=100", userToken, "installations")) {
            JsonNode id = n.path("id");
            if (id.canConvertToLong()) ids.add(id.asLong());
        }
        return Set.copyOf(ids);
    }

    /** Without paging, more than 100 repos or installations would be silently truncated. */
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
            } catch (Exception e) {
                throw new TessaryException(GitError.TOKEN_MINT_FAILED, e, LABEL);
            }
            next = nextLink(res.headers().firstValue("Link").orElse(null));
        }
        // A cursor still present at the cap means silent truncation; log it, categorical only.
        if (next != null) {
            log.warn("github pagination capped field={} maxPages={}", arrayField, MAX_PAGES);
        }
        return out;
    }

    static String nextLink(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) return null;
        // e.g. <https://api.github.com/...&page=2>; rel="next", <...&page=9>; rel="last"
        for (String part : linkHeader.split(",")) {
            String seg = part.trim();
            int lt = seg.indexOf('<');
            int gt = seg.indexOf('>', lt + 1);
            // Match the full "; rel="next"" param (RFC 5988) so rel="next-page" doesn't false-match.
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
     * GitHub App keys download as PKCS#1; PKCS#1 is wrapped into PKCS#8 so one {@link KeyFactory} path handles both
     * without a third-party provider.
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

    private static byte[] derTlv(byte tag, byte[] content) {
        byte[] len = derLength(content.length);
        byte[] out = new byte[1 + len.length + content.length];
        out[0] = tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(content, 0, out, 1 + len.length, content.length);
        return out;
    }

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
