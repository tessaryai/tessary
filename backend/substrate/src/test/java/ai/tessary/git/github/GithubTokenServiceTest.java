// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static ai.tessary.git.github.ScriptedHttpClient.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.github.GithubTokenService.InstalledRepo;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The GitHub token service against a scripted transport: App-mode installation tokens (the signed JWT that
 * asks for one, the cache that serves it, the per-integration lock that collapses a burst of misses), the
 * paginated list endpoints, and the OAuth code exchange that proves who completed an install. Every host is a
 * TEST-NET-3 address so {@code UrlGuard} passes without DNS.
 */
class GithubTokenServiceTest {

    private static final String HOST = "203.0.113.10";
    private static final String WEB = "https://203.0.113.20";
    private static final String API = "https://203.0.113.30";
    private static final String MINT = "https://" + HOST + "/app/installations/77/access_tokens";
    private static final String REPOS = "https://" + HOST + "/installation/repositories?per_page=100";

    private static final KeyPair APP_KEY = rsaKeyPair();

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretBox box = secretBox();
    private final ScriptedHttpClient http = new ScriptedHttpClient();

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 5);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    private static GithubAppProperties configuredApp() {
        GithubAppProperties props = new GithubAppProperties();
        props.setAppId("4242");
        props.setPrivateKeyPem("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(APP_KEY.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        return props;
    }

    private GithubTokenService service(GithubAppProperties props) {
        return new GithubTokenService(props, box, mapper, http, WEB, API);
    }

    private GitIntegrationRow integration(String credentialsEnc) {
        return new GitIntegrationRow("i1", "p1", "github", HOST, "acme", "web", "main", credentialsEnc, "t", "t");
    }

    private GitIntegrationRow appIntegration() {
        return integration(box.seal("{\"installationId\":77}"));
    }

    private static String tokenJson(String token, Instant expiresAt) {
        return "{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}";
    }

    // ---- App-mode installation tokens ---------------------------------------------------------

    @Test
    void appMode_asksWithAJwtSignedByTheAppKey_andServesTheTokenFromCacheWhileFresh() throws Exception {
        http.on(MINT, response(201, tokenJson("ghs_1", Instant.now().plusSeconds(3600))));
        GithubTokenService svc = service(configuredApp());

        assertEquals("Bearer ghs_1", svc.authHeader(appIntegration()));
        assertEquals("Bearer ghs_1", svc.authHeader(appIntegration()));

        assertEquals(1, http.sent().size(), "a fresh cached token is served without a second mint");
        HttpRequest mint = http.sent().get(0);
        assertEquals("POST", mint.method());
        String jwt = mint.headers().firstValue("Authorization").orElseThrow().substring("Bearer ".length());
        String[] parts = jwt.split("\\.");
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(APP_KEY.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(
                verify.verify(Base64.getUrlDecoder().decode(parts[2])),
                "GitHub rejects a JWT the App key did not sign");
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("RS256", header.path("alg").asText());
        assertEquals("4242", claims.path("iss").asText(), "the issuer is the App id");
        // GitHub caps an App JWT at ten minutes; iat is backdated a minute for clock skew.
        assertEquals(600, claims.path("exp").asLong() - claims.path("iat").asLong());
    }

    @Test
    void appMode_mintsAgainOnceTheCachedTokenIsInsideTheRefreshMargin() {
        http.on(
                MINT,
                response(201, tokenJson("ghs_old", Instant.now().plusSeconds(30))),
                response(201, tokenJson("ghs_new", Instant.now().plusSeconds(3600))));
        GithubTokenService svc = service(configuredApp());

        assertEquals("Bearer ghs_old", svc.authHeader(appIntegration()));
        // Thirty seconds left is inside the sixty-second margin: serving it would hand a clone a token
        // that expires mid-fetch.
        assertEquals("Bearer ghs_new", svc.authHeader(appIntegration()));
        assertEquals(2, http.sent().size());
    }

    @Test
    void appMode_concurrentMissesOnOneIntegrationShareOneMint() throws Exception {
        CountDownLatch inMint = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        http.on(MINT, (ScriptedHttpClient.Deferred) () -> {
            inMint.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("mint never released");
            return response(201, tokenJson("ghs_1", Instant.now().plusSeconds(3600)));
        });
        GithubTokenService svc = service(configuredApp());

        CompletableFuture<String> first = new CompletableFuture<>();
        Thread a = new Thread(() -> first.complete(svc.authHeader(appIntegration())));
        a.start();
        assertTrue(inMint.await(10, TimeUnit.SECONDS), "the first caller reached the mint");
        CompletableFuture<String> second = new CompletableFuture<>();
        Thread b = new Thread(() -> second.complete(svc.authHeader(appIntegration())));
        b.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (b.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) fail("the second caller never queued on the mint lock");
            Thread.onSpinWait();
        }
        release.countDown();

        assertEquals("Bearer ghs_1", first.get(10, TimeUnit.SECONDS));
        assertEquals("Bearer ghs_1", second.get(10, TimeUnit.SECONDS));
        assertEquals(1, http.sent().size(), "the queued caller reuses the token the first one minted");
    }

    /**
     * Every way a mint can come back unusable surfaces as TOKEN_MINT_FAILED, never as a raw parse or
     * transport exception the caller would turn into an opaque 500.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "500|{}",
                "201|{\"expires_at\":\"2099-01-01T00:00:00Z\"}",
                "201|{\"token\":\"ghs_1\"}",
                "201|{\"token\":\" \",\"expires_at\":\"2099-01-01T00:00:00Z\"}",
                "201|{\"token\":\"ghs_1\",\"expires_at\":\" \"}",
                "201|{\"token\":\"ghs_1\",\"expires_at\":\"tomorrow\"}",
                "201|not json"
            })
    void appMode_anUnusableMintIsTokenMintFailed(String answer) {
        String[] parts = answer.split("\\|", 2);
        http.on(MINT, response(Integer.parseInt(parts[0]), parts[1]));

        TessaryException e = assertThrows(
                TessaryException.class, () -> service(configuredApp()).authHeader(appIntegration()));
        assertEquals(GitError.TOKEN_MINT_FAILED, e.error());
    }

    @Test
    void appMode_anAppKeyThatDoesNotParseFailsBeforeAnyRequest() {
        GithubAppProperties props = configuredApp();
        props.setPrivateKeyPem("-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----");

        TessaryException e =
                assertThrows(TessaryException.class, () -> service(props).authHeader(appIntegration()));
        assertEquals(GitError.TOKEN_MINT_FAILED, e.error());
        assertTrue(http.sent().isEmpty(), "nothing is sent without a signed JWT");
    }

    /** A transport failure is TOKEN_MINT_FAILED, and an interrupt is handed back rather than swallowed. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void appMode_aTransportFailureIsTokenMintFailed(boolean interrupted) {
        http.on(MINT, interrupted ? new InterruptedException() : new IOException("reset"));

        TessaryException e = assertThrows(
                TessaryException.class, () -> service(configuredApp()).authHeader(appIntegration()));
        assertEquals(GitError.TOKEN_MINT_FAILED, e.error());
        assertEquals(interrupted, Thread.interrupted(), "the interrupt flag is restored for the caller");
    }

    /**
     * A row with no usable PAT and no usable installation id is INSTALLATION_NOT_FOUND before anything is
     * minted: no credentials at all, a blank blob, a blob that is not sealed by this key, a sealed blob that is not JSON,
     * and sealed JSON whose token is null or blank and whose installation id is missing or not a number.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "unsealed",
                "sealed:not json",
                "sealed:{}",
                "sealed:{\"token\":null}",
                "sealed:{\"token\":\"  \"}",
                "sealed:{\"installationId\":\"abc\"}"
            })
    void appMode_credentialsWithoutAnInstallationIdAreInstallationNotFound(String credentials) {
        String enc = credentials != null && credentials.startsWith("sealed:")
                ? box.seal(credentials.substring("sealed:".length()))
                : credentials;

        TessaryException e = assertThrows(
                TessaryException.class, () -> service(configuredApp()).authHeader(integration(enc)));
        assertEquals(GitError.INSTALLATION_NOT_FOUND, e.error());
        assertTrue(http.sent().isEmpty());
    }

    // ---- post-install: the bare-installation-id mint and the repo list ------------------------

    @Test
    void mintByInstallationId_refusesWhenNoAppIsConfigured() {
        TessaryException e = assertThrows(
                TessaryException.class, () -> service(new GithubAppProperties()).mintInstallationToken(77L, HOST));
        assertEquals(GitError.MISSING_APP_CONFIG, e.error());
    }

    @Test
    void listInstallationRepos_followsTheNextCursorAndKeepsOnlyNamedRepos() {
        String page2 = "https://" + HOST + "/installation/repositories?per_page=100&page=2";
        http.on(MINT, response(201, tokenJson("ghs_1", Instant.now().plusSeconds(3600))))
                .on(REPOS, response(200, """
                                {"repositories":[
                                  {"owner":{"login":"acme"},"name":"web","default_branch":"trunk"},
                                  {"owner":{"login":"acme"}},
                                  {"name":"orphan"}
                                ]}""", page2))
                .on(page2, response(200, "{\"repositories\":[{\"owner\":{\"login\":\"acme\"},\"name\":\"api\"}]}"));

        List<InstalledRepo> repos = service(configuredApp()).listInstallationRepos(77L, HOST);

        assertEquals(
                List.of(new InstalledRepo("acme", "web", "trunk"), new InstalledRepo("acme", "api", "main")), repos);
        assertEquals(
                "Bearer ghs_1",
                http.sent().get(1).headers().firstValue("Authorization").orElseThrow(),
                "the list is read with the installation token, not the App JWT");
    }

    @Test
    void listInstallationRepos_stopsAtThePageCapWhenTheCursorNeverEnds() {
        http.on(MINT, response(201, tokenJson("ghs_1", Instant.now().plusSeconds(3600))))
                .on(
                        REPOS,
                        response(200, "{\"repositories\":[{\"owner\":{\"login\":\"acme\"},\"name\":\"web\"}]}", REPOS));

        List<InstalledRepo> repos = service(configuredApp()).listInstallationRepos(77L, HOST);

        assertEquals(50, repos.size(), "one repo per page, fifty pages");
        assertEquals(51, http.sent().size(), "one mint, then exactly fifty page reads: a looping cursor must end");
    }

    /** A page that fails or does not parse fails the whole list, rather than returning a silent partial one. */
    @ParameterizedTest
    @ValueSource(strings = {"404", "not json", "io", "interrupt"})
    void listInstallationRepos_aBadPageIsTokenMintFailed(String failure) {
        Object page =
                switch (failure) {
                    case "404" -> response(404, "{}");
                    case "not json" -> response(200, "not json");
                    case "io" -> new IOException("reset");
                    default -> new InterruptedException();
                };
        http.on(MINT, response(201, tokenJson("ghs_1", Instant.now().plusSeconds(3600))))
                .on(REPOS, page);

        TessaryException e = assertThrows(
                TessaryException.class, () -> service(configuredApp()).listInstallationRepos(77L, HOST));
        assertEquals(GitError.TOKEN_MINT_FAILED, e.error());
        assertEquals("interrupt".equals(failure), Thread.interrupted(), "only an interrupt sets the flag");
    }

    /** Only an array is a page of repos: an object where the array belongs is not read as one. */
    @Test
    void listInstallationRepos_aPageWithoutTheArrayContributesNothing() {
        http.on(MINT, response(201, tokenJson("ghs_1", Instant.now().plusSeconds(3600))))
                .on(
                        REPOS,
                        response(200, "{\"repositories\":{\"x\":{\"owner\":{\"login\":\"acme\"},\"name\":\"web\"}}}"));

        assertEquals(List.of(), service(configuredApp()).listInstallationRepos(77L, HOST));
    }

    // ---- OAuth: who completed the install -----------------------------------------------------

    private static GithubAppProperties withOAuth() {
        GithubAppProperties props = configuredApp();
        props.setClientId("Iv1.client");
        props.setClientSecret("s&cret=1");
        return props;
    }

    @Test
    void exchangeUserCode_postsTheEncodedFormAndReturnsTheUserToken() {
        http.on(WEB + "/login/oauth/access_token", response(200, "{\"access_token\":\"ghu_1\"}"));

        assertEquals("ghu_1", service(withOAuth()).exchangeUserCode("c/1 2"));

        HttpRequest req = http.sent().get(0);
        assertEquals("POST", req.method());
        assertEquals(
                "client_id=Iv1.client&client_secret=s%26cret%3D1&code=c%2F1+2",
                ScriptedHttpClient.body(req),
                "every field is form-encoded, so a secret with & or = cannot split the form");
    }

    @Test
    void exchangeUserCode_withoutOAuthCredentialsCannotVerifyTheInstaller() {
        TessaryException e = assertThrows(
                TessaryException.class, () -> service(configuredApp()).exchangeUserCode("c"));
        assertEquals(GitError.INSTALL_NOT_VERIFIED, e.error());
        assertTrue(http.sent().isEmpty());
    }

    /**
     * GitHub answers a bad or expired code with 200 and an error body, so a missing token is the installer
     * not being verified; a non-2xx or a transport failure is the exchange itself failing.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "200|{\"error\":\"bad_verification_code\"}",
                "200|{\"access_token\":\"\"}",
                "502|{}",
                "io",
                "interrupt"
            })
    void exchangeUserCode_failuresAreTypedByWhatWentWrong(String answer) {
        Object res =
                switch (answer) {
                    case "io" -> new IOException("reset");
                    case "interrupt" -> new InterruptedException();
                    default -> {
                        String[] parts = answer.split("\\|", 2);
                        yield response(Integer.parseInt(parts[0]), parts[1]);
                    }
                };
        http.on(WEB + "/login/oauth/access_token", res);

        TessaryException e =
                assertThrows(TessaryException.class, () -> service(withOAuth()).exchangeUserCode("c"));
        assertEquals(answer.startsWith("200") ? GitError.INSTALL_NOT_VERIFIED : GitError.TOKEN_MINT_FAILED, e.error());
        assertEquals("interrupt".equals(answer), Thread.interrupted());
    }

    @Test
    void listUserInstallations_collectsNumericIdsAcrossPages() {
        String first = API + "/user/installations?per_page=100";
        String second = first + "&page=2";
        http.on(first, response(200, "{\"installations\":[{\"id\":11},{\"id\":\"not-a-number\"},{}]}", second))
                .on(second, response(200, "{\"installations\":[{\"id\":22},{\"id\":11}]}"));

        assertEquals(Set.of(11L, 22L), service(configuredApp()).listUserInstallations("ghu_1"));
        assertEquals(
                "Bearer ghu_1",
                http.sent().get(0).headers().firstValue("Authorization").orElseThrow(),
                "the list is the USER's installations, read with the user token");
        List<String> uris = new ArrayList<>();
        for (HttpRequest r : http.sent()) uris.add(r.uri().toString());
        assertEquals(List.of(first, second), uris);
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
