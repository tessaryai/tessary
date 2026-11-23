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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubClient implements GitProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GithubClient.class);
    private static final String LABEL = "github";
    /** For messages a person reads. */
    private static final String LABEL_TITLE = "GitHub";

    private final GithubTokenService tokenService;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public GithubClient(GithubTokenService tokenService, ObjectMapper mapper) {
        this(
                tokenService,
                mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Test seam for the transport. */
    GithubClient(GithubTokenService tokenService, ObjectMapper mapper, HttpClient http) {
        this.tokenService = tokenService;
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public GitProvider provider() {
        return GitProvider.GITHUB;
    }

    /**
     * GitHub returns 404, not 403, for a private repo a fine-grained token was not granted, so 404 cannot mean "no such
     * repository". 401 is unambiguous, 403 is an explicit refusal (SSO or org policy), and any other non-2xx is a plain
     * provider failure.
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

    /** Null means not a refusal: parse the body. */
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

    // ---- write access ----------------------------------------------------------------------------

    /**
     * An App connection reads the permissions GitHub returned with its installation token. A PAT connection reads the
     * repository's {@code permissions.push}, which reflects the user's role and not always the token's grants, so for a
     * PAT this is best effort and a refused write still fails with {@link GitError#WRITE_ACCESS_MISSING}.
     */
    @Override
    public WriteAccess writeAccess(GitIntegrationRow integ) {
        Optional<Map<String, String>> granted = tokenService.installationPermissions(integ, false);
        if (granted.isPresent()) {
            if (appCanWrite(granted.get())) return WriteAccess.granted();
            // The cached token can predate a grant the owner accepted since; ask once more with a new one.
            boolean now = tokenService
                    .installationPermissions(integ, true)
                    .map(GithubClient::appCanWrite)
                    .orElse(false);
            return now ? WriteAccess.granted() : WriteAccess.blocked(WriteBlock.APP_PERMISSION_MISSING);
        }
        HttpResponse<String> res = call(integ, "GET", repoPath(integ), null);
        TessaryException refusal = verifyRefusal(res.statusCode(), slug(integ));
        if (refusal != null) throw refusal;
        boolean push = validateAndParse(res).path("permissions").path("push").asBoolean(false);
        return push ? WriteAccess.granted() : WriteAccess.blocked(WriteBlock.TOKEN_CANNOT_PUSH);
    }

    private static boolean appCanWrite(Map<String, String> permissions) {
        return "write".equals(permissions.get("contents")) && "write".equals(permissions.get("pull_requests"));
    }

    // ---- commits and refs ------------------------------------------------------------------------

    @Override
    public String createBlob(GitIntegrationRow integ, byte[] content) {
        ObjectNode body = mapper.createObjectNode()
                .put("content", Base64.getEncoder().encodeToString(content))
                .put("encoding", "base64");
        return request(integ, "POST", repoPath(integ) + "/git/blobs", body)
                .path("sha")
                .asText();
    }

    @Override
    public String commitTreeSha(GitIntegrationRow integ, String commitSha) {
        return request(integ, "GET", repoPath(integ) + "/git/commits/" + enc(commitSha), null)
                .path("tree")
                .path("sha")
                .asText();
    }

    @Override
    public String createTree(GitIntegrationRow integ, String baseTreeSha, List<TreeEntry> entries) {
        ObjectNode body = mapper.createObjectNode().put("base_tree", baseTreeSha);
        ArrayNode tree = body.putArray("tree");
        for (TreeEntry e : entries) {
            ObjectNode n =
                    tree.addObject().put("path", e.path()).put("mode", e.mode()).put("type", "blob");
            // GitHub deletes a path only on an explicit null sha, so it is written even if the mapper drops nulls.
            if (e.blobSha() == null) n.putNull("sha");
            else n.put("sha", e.blobSha());
        }
        return request(integ, "POST", repoPath(integ) + "/git/trees", body)
                .path("sha")
                .asText();
    }

    @Override
    public String createCommit(GitIntegrationRow integ, String message, String treeSha, String parentSha) {
        ObjectNode body = mapper.createObjectNode().put("message", message).put("tree", treeSha);
        body.putArray("parents").add(parentSha);
        return request(integ, "POST", repoPath(integ) + "/git/commits", body)
                .path("sha")
                .asText();
    }

    @Override
    public Optional<String> branchSha(GitIntegrationRow integ, String branch) {
        HttpResponse<String> res = call(integ, "GET", repoPath(integ) + "/git/ref/heads/" + refPath(branch), null);
        if (res.statusCode() == 404) return Optional.empty();
        return Optional.of(parse(integ, res).path("object").path("sha").asText());
    }

    @Override
    public void createBranch(GitIntegrationRow integ, String branch, String sha) {
        ObjectNode body = mapper.createObjectNode()
                .put("ref", "refs/heads/" + validBranch(branch))
                .put("sha", sha);
        HttpResponse<String> res = call(integ, "POST", repoPath(integ) + "/git/refs", body);
        // 422 is "Reference already exists": another writer created it first.
        if (res.statusCode() == 422) throw new TessaryException(GitError.BRANCH_MOVED, branch, slug(integ));
        parse(integ, res);
    }

    @Override
    public void updateBranch(GitIntegrationRow integ, String branch, String sha) {
        ObjectNode body = mapper.createObjectNode().put("sha", sha).put("force", false);
        HttpResponse<String> res = call(integ, "PATCH", repoPath(integ) + "/git/refs/heads/" + refPath(branch), body);
        // 422 is "Update is not a fast forward": a push landed first.
        if (res.statusCode() == 422) throw new TessaryException(GitError.BRANCH_MOVED, branch, slug(integ));
        parse(integ, res);
    }

    @Override
    public List<String> changedPaths(GitIntegrationRow integ, String baseSha, String headSha) {
        JsonNode cmp = request(integ, "GET", repoPath(integ) + "/compare/" + enc(baseSha) + "..." + enc(headSha), null);
        Set<String> paths = new LinkedHashSet<>();
        for (JsonNode f : cmp.path("files")) {
            String name = f.path("filename").asText("");
            String previous = f.path("previous_filename").asText("");
            if (!name.isEmpty()) paths.add(name);
            if (!previous.isEmpty()) paths.add(previous);
        }
        return List.copyOf(paths);
    }

    // ---- pull requests ---------------------------------------------------------------------------

    @Override
    public PullRequest createPullRequest(GitIntegrationRow integ, String head, String base, String title, String body) {
        ObjectNode req = mapper.createObjectNode()
                .put("title", title)
                .put("head", head)
                .put("base", base)
                .put("body", body)
                .put("draft", false);
        return pullRequest(request(integ, "POST", repoPath(integ) + "/pulls", req));
    }

    @Override
    public Optional<PullRequest> findPullRequest(GitIntegrationRow integ, String head) {
        String query =
                "?head=" + enc(integ.repoOwner() + ":" + head) + "&state=all&sort=created&direction=desc&per_page=1";
        JsonNode list = request(integ, "GET", repoPath(integ) + "/pulls" + query, null);
        if (!list.isArray() || list.isEmpty()) return Optional.empty();
        return Optional.of(pullRequest(list.get(0)));
    }

    @Override
    public PullRequest pullRequest(GitIntegrationRow integ, int number) {
        HttpResponse<String> res = call(integ, "GET", repoPath(integ) + "/pulls/" + number, null);
        if (res.statusCode() == 404) throw new TessaryException(GitError.PR_NOT_FOUND, number, slug(integ));
        return pullRequest(parse(integ, res));
    }

    @Override
    public void updatePullRequestBody(GitIntegrationRow integ, int number, String body) {
        ObjectNode req = mapper.createObjectNode().put("body", body);
        HttpResponse<String> res = call(integ, "PATCH", repoPath(integ) + "/pulls/" + number, req);
        if (res.statusCode() == 404) throw new TessaryException(GitError.PR_NOT_FOUND, number, slug(integ));
        parse(integ, res);
    }

    /** {@code merged_at} is set in both the list and the single answer, unlike {@code merged}. */
    private static PullRequest pullRequest(JsonNode pr) {
        JsonNode mergedAt = pr.path("merged_at");
        return new PullRequest(
                pr.path("number").asInt(),
                pr.path("html_url").asText(""),
                pr.path("state").asText(""),
                mergedAt.isTextual() && !mergedAt.asText().isBlank(),
                pr.path("head").path("ref").asText(""),
                pr.path("head").path("sha").asText(""),
                pr.path("base").path("ref").asText(""));
    }

    // ---- transport -------------------------------------------------------------------------------

    /** Sends and maps the answer: a refusal is typed, any other non-2xx is a provider failure. */
    private JsonNode request(GitIntegrationRow integ, String method, String path, @Nullable JsonNode body) {
        return parse(integ, call(integ, method, path, body));
    }

    private JsonNode parse(GitIntegrationRow integ, HttpResponse<String> res) {
        TessaryException refusal = refusal(res, slug(integ));
        if (refusal != null) throw refusal;
        return validateAndParse(res);
    }

    /**
     * For the commit, ref and pull request calls. 401 is a dead credential. 403 is a missing grant, unless GitHub says
     * it is a rate limit (no requests left, or a {@code retry-after}), which is a provider failure like 429. Null means
     * not a refusal.
     */
    static @Nullable TessaryException refusal(HttpResponse<?> res, String slug) {
        int status = res.statusCode();
        boolean limited = status == 429
                || (status == 403
                        && (res.headers().firstValue("retry-after").isPresent()
                                || "0"
                                        .equals(res.headers()
                                                .firstValue("x-ratelimit-remaining")
                                                .orElse(null))));
        if (limited) return new TessaryException(GitError.PROVIDER_CALL_FAILED, LABEL, "rate limited");
        return switch (status) {
            case 401 -> new TessaryException(GitError.CREDENTIALS_REJECTED, LABEL_TITLE);
            case 403 -> new TessaryException(GitError.WRITE_ACCESS_MISSING, slug);
            default -> null;
        };
    }

    /** Same guards as every read: public host only, bounded body, the integration's own credential. */
    private HttpResponse<String> call(GitIntegrationRow integ, String method, String path, @Nullable JsonNode body) {
        URI uri = UrlGuard.requirePublicHttp(baseUrl(integ) + path);
        HttpRequest.Builder req = baseRequest(integ, uri);
        if (body == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            req.method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
                    .header("Content-Type", "application/json");
        }
        return exchange(req.build());
    }

    private static String repoPath(GitIntegrationRow integ) {
        return "/repos/" + enc(integ.repoOwner()) + "/" + enc(integ.repoName());
    }

    private static String slug(GitIntegrationRow integ) {
        return integ.repoOwner() + "/" + integ.repoName();
    }

    // Git's own ref-name rules, enough that no segment can climb out of the refs path.
    private static final Pattern BAD_BRANCH = Pattern.compile(
            "(^[/.])|(/$)|(\\.$)|(\\.lock$)|(//)|(\\.\\.)|(/\\.)|(@\\{)|[\\x00-\\x20\\x7f~^:?*\\[\\\\]");

    static String validBranch(String branch) {
        if (branch == null || branch.isBlank() || BAD_BRANCH.matcher(branch).find()) {
            throw new IllegalArgumentException("not a valid branch name");
        }
        return branch;
    }

    /**
     * A branch inside a refs path keeps its slashes (refs/heads/a/b), unlike {@code /branches/{b}}, which takes it as
     * one segment. Each segment is encoded.
     */
    static String refPath(String branch) {
        StringBuilder out = new StringBuilder();
        for (String seg : validBranch(branch).split("/")) {
            if (!out.isEmpty()) out.append('/');
            out.append(enc(seg));
        }
        return out.toString();
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

    /** Maps only transport failures; status is the caller's call. */
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
