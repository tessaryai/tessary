// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProvider;
import ai.tessary.git.GitProviderClient;
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
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GithubClient implements GitProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GithubClient.class);
    private static final String LABEL = "github";
    /** The provider's own spelling, for messages a person reads rather than log lines. */
    private static final String LABEL_TITLE = "GitHub";

    private final GithubTokenService tokenService;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GithubClient(GithubTokenService tokenService, ObjectMapper mapper) {
        this.tokenService = tokenService;
        this.mapper = mapper;
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public GitProvider provider() {
        return GitProvider.GITHUB;
    }

    /**
     * {@code GET /repos/{owner}/{repo}} with the integration's own credentials. The status mapping is
     * the point of the method: GitHub returns 404 (not 403) for a private repo a fine-grained token
     * has not been granted, so a 404 cannot be reported as "no such repository" without frequently
     * being wrong. 401 is unambiguous, 403 is an explicit refusal (SSO or an org policy), and every
     * other non-2xx stays a plain provider failure rather than being read as a verdict about access.
     */
    @Override
    public RepoAccess verifyAccess(GitIntegrationRow integ) {
        String slug = integ.repoOwner() + "/" + integ.repoName();
        URI uri = UrlGuard.requirePublicHttp(
                baseUrl(integ) + "/repos/" + enc(integ.repoOwner()) + "/" + enc(integ.repoName()));
        HttpResponse<String> res = exchange(baseRequest(integ, uri).GET().build());
        TessaryException refusal = verifyRefusal(res.statusCode(), slug);
        if (refusal != null) throw refusal;
        JsonNode repo = validateAndParse(res);
        String branch = repo.path("default_branch").asText("");
        return new RepoAccess(branch.isBlank() ? "main" : branch);
    }

    /**
     * The status-to-error mapping for {@link #verifyAccess}, split out so it can be pinned without
     * an HTTP round trip ({@code UrlGuard} refuses to aim this client at a local test server).
     * Null means the status is not a refusal and the body should be parsed.
     */
    static @Nullable TessaryException verifyRefusal(int status, String slug) {
        return switch (status) {
            case 401 -> new TessaryException(GitError.CREDENTIALS_REJECTED, LABEL_TITLE);
            case 403 -> new TessaryException(GitError.REPO_ACCESS_DENIED, slug);
            case 404 -> new TessaryException(GitError.REPO_UNREACHABLE, slug);
            default -> null;
        };
    }

    @Override
    public String resolveHeadSha(GitIntegrationRow integ, String branch) {
        String b = (branch != null && !branch.isBlank()) ? branch : integ.defaultBranch();
        JsonNode res = get(integ, "/repos/" + integ.repoOwner() + "/" + integ.repoName() + "/branches/" + enc(b));
        return res.path("commit").path("sha").asText();
    }

    private JsonNode get(GitIntegrationRow integ, String path) {
        URI uri = UrlGuard.requirePublicHttp(baseUrl(integ) + path);
        HttpRequest req = baseRequest(integ, uri).GET().build();
        return send(req);
    }

    private HttpRequest.Builder baseRequest(GitIntegrationRow integ, URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", tokenService.authHeader(integ))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(60));
    }

    private JsonNode send(HttpRequest req) {
        return validateAndParse(exchange(req));
    }

    /** Execute the request, mapping only transport failures to an exception (status is the caller's call). */
    private HttpResponse<String> exchange(HttpRequest req) {
        UrlGuard.requirePublicHttp(req.uri().toString());
        try {
            return http.send(req, BoundedBody.string());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("github api network error");
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, e, LABEL, "network error");
        }
    }

    /** Require 2xx, then parse the JSON body. */
    private JsonNode validateAndParse(HttpResponse<String> res) {
        int status = res.statusCode();
        if (status / 100 != 2) {
            log.warn("github api rejected status={}", status);
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, LABEL, "HTTP " + status);
        }
        try {
            return mapper.readTree(res.body());
        } catch (Exception e) {
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, e, LABEL, "non-JSON response");
        }
    }

    private static String baseUrl(GitIntegrationRow integ) {
        String host = (integ.host() == null || integ.host().isBlank()) ? "api.github.com" : integ.host();
        return "https://" + host;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
