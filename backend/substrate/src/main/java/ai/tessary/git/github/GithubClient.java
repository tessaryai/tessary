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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
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

    /** Bounded concurrency for fetching bundle blob contents (kept low to avoid GitHub secondary limits). */
    private static final int BLOB_FETCH_CONCURRENCY = 8;

    private final GithubTokenService tokenService;
    private final ObjectMapper mapper;
    private final HttpClient http;
    // Bundle-blob fetches are blocking GitHub HTTP GETs, so they run on virtual threads (one per
    // task, named for diagnosability). The pool itself is unbounded — concurrency is bounded by the
    // BLOB_FETCH_CONCURRENCY semaphore acquired around each fetch, preserving the "stay under GitHub
    // secondary rate limits" cap while letting parked tasks cost almost nothing.
    private final ExecutorService blobPool = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("github-blob-fetch-", 0).factory());
    private final Semaphore blobPermits = new Semaphore(BLOB_FETCH_CONCURRENCY);

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

    @Override
    public CommitComparison compare(GitIntegrationRow integ, String baseSha, String headSha) {
        JsonNode res = get(
                integ,
                "/repos/" + integ.repoOwner() + "/" + integ.repoName() + "/compare/" + enc(baseSha) + "..."
                        + enc(headSha));
        List<ChangedFile> files = new ArrayList<>();
        JsonNode fileNodes = res.path("files");
        if (fileNodes.isArray()) {
            for (JsonNode f : fileNodes) {
                files.add(new ChangedFile(
                        f.path("filename").asText(), f.path("status").asText()));
            }
        }
        int aheadBy = res.path("ahead_by").asInt();
        String status = res.path("status").asText("");
        String base = res.path("base_commit").path("sha").asText(baseSha);
        String head = res.path("merge_base_commit").has("sha")
                ? res.path("commits").isArray() && !res.path("commits").isEmpty()
                        ? res.path("commits")
                                .get(res.path("commits").size() - 1)
                                .path("sha")
                                .asText(headSha)
                        : headSha
                : headSha;
        return new CommitComparison(base, head, aheadBy, status, files);
    }

    @Override
    public boolean isAncestor(GitIntegrationRow integ, String ancestorSha, String descendantSha) {
        JsonNode res = get(
                integ,
                "/repos/" + integ.repoOwner() + "/" + integ.repoName() + "/compare/" + enc(ancestorSha) + "..."
                        + enc(descendantSha));
        String status = res.path("status").asText("");
        return "ahead".equals(status) || "identical".equals(status);
    }

    @Override
    public List<RepoFile> getTreeFiles(GitIntegrationRow integ, String sha, String pathPrefix) {
        String repo = "/repos/" + integ.repoOwner() + "/" + integ.repoName();
        String prefix = pathPrefix == null ? "" : pathPrefix;
        // Scope the recursive tree fetch to the bundle's top-level dir (e.g. ".tessary")
        // instead of the whole repo. GitHub truncates large recursive trees; on a big
        // monorepo a truncated listing would make the hard-delete import drop graders that
        // actually exist. The bundle subtree is small and won't truncate.
        String topDir = topDir(prefix);

        JsonNode tree;
        String basePrefix;
        if (topDir.isEmpty()) {
            tree = get(integ, repo + "/git/trees/" + enc(sha) + "?recursive=1");
            basePrefix = "";
        } else {
            // Resolve the commit's root tree, find the top-level dir, recurse into just it.
            String subtreeSha = subtreeSha(integ, sha, topDir);
            if (subtreeSha == null) return List.of(); // dir absent at this commit — no bundle
            tree = get(integ, repo + "/git/trees/" + enc(subtreeSha) + "?recursive=1");
            basePrefix = topDir + "/";
        }
        // A truncated (partial) listing must never drive the hard-delete import — fail safe.
        if (tree.path("truncated").asBoolean(false)) {
            log.warn("github tree truncated for prefix={} — refusing partial bundle import", prefix);
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, LABEL, "tree truncated for " + prefix);
        }
        // Collect the matching blob entries, then fetch their contents concurrently. The tree
        // already carries each blob's sha, so we fetch blobs directly (one call each) and skip
        // the per-file /contents round-trip.
        record Blob(String path, String sha) {}
        List<Blob> blobs = new ArrayList<>();
        for (JsonNode entry : tree.path("tree")) {
            if (!"blob".equals(entry.path("type").asText())) continue;
            String path = basePrefix + entry.path("path").asText();
            if (!path.startsWith(prefix)) continue;
            blobs.add(new Blob(path, entry.path("sha").asText(null)));
        }
        if (blobs.isEmpty()) return List.of();

        // Submit each blob onto the shared virtual-thread executor; the semaphore caps how many
        // fetches run concurrently (BLOB_FETCH_CONCURRENCY) so we stay under GitHub's secondary
        // rate limits. Excess tasks park on the permit, not on a thread.
        List<Future<RepoFile>> futures = new ArrayList<>(blobs.size());
        for (Blob b : blobs) {
            futures.add(blobPool.submit(() -> {
                blobPermits.acquire();
                try {
                    return new RepoFile(b.path(), fetchFileContent(integ, sha, b.path(), b.sha()));
                } finally {
                    blobPermits.release();
                }
            }));
        }
        try {
            List<RepoFile> out = new ArrayList<>(blobs.size());
            for (Future<RepoFile> f : futures) out.add(f.get());
            return List.copyOf(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, e, LABEL, "interrupted fetching bundle");
        } catch (ExecutionException e) {
            // Surface the worker's original TessaryException (its own stack is intact).
            if (e.getCause() instanceof TessaryException ee) throw ee; // NOPMD PreserveStackTrace
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, e, LABEL, "bundle blob fetch failed");
        }
    }

    /** Top-level dir component of a path prefix (e.g. ".tessary/graders" → ".tessary"); "" for a root prefix. */
    private static String topDir(String pathPrefix) {
        String p = pathPrefix == null ? "" : pathPrefix;
        while (p.startsWith("/")) p = p.substring(1);
        int slash = p.indexOf('/');
        return slash >= 0 ? p.substring(0, slash) : p;
    }

    /** The tree sha of {@code topDir} at {@code commitSha}, or null when that dir is absent. */
    private String subtreeSha(GitIntegrationRow integ, String commitSha, String topDir) {
        String repo = "/repos/" + integ.repoOwner() + "/" + integ.repoName();
        JsonNode root = get(integ, repo + "/git/trees/" + enc(commitSha));
        for (JsonNode e : root.path("tree")) {
            if ("tree".equals(e.path("type").asText())
                    && topDir.equals(e.path("path").asText())) {
                return e.path("sha").asText(null);
            }
        }
        return null;
    }

    @Override
    public boolean bundleDirExists(GitIntegrationRow integ, String sha, String pathPrefix) {
        String topDir = topDir(pathPrefix);
        // A root prefix trivially "exists" — it must never read as absent and trigger a bootstrap.
        return topDir.isEmpty() || subtreeSha(integ, sha, topDir) != null;
    }

    @Override
    public boolean bundleDirChanged(GitIntegrationRow integ, String baseSha, String headSha, String pathPrefix) {
        String topDir = topDir(pathPrefix);
        if (topDir.isEmpty()) return true; // whole-repo prefix can't be isolated cheaply — assume changed
        String before = subtreeSha(integ, baseSha, topDir);
        String after = subtreeSha(integ, headSha, topDir);
        // Differing tree shas (or present on one side only) mean the bundle dir changed.
        return !java.util.Objects.equals(before, after);
    }

    private String fetchFileContent(GitIntegrationRow integ, String ref, String path, String blobSha) {
        // Prefer the blob endpoint (we already have the sha from the tree — one call, no /contents).
        if (blobSha != null && !blobSha.isBlank()) {
            JsonNode blob =
                    get(integ, "/repos/" + integ.repoOwner() + "/" + integ.repoName() + "/git/blobs/" + enc(blobSha));
            String blobContent = blob.path("content").asText("");
            if (!blobContent.isBlank()) {
                return new String(Base64.getMimeDecoder().decode(blobContent), StandardCharsets.UTF_8);
            }
        }
        // Fallback (sha absent): the contents API by path.
        JsonNode contents = get(
                integ,
                "/repos/" + integ.repoOwner() + "/" + integ.repoName() + "/contents/" + encPath(path) + "?ref="
                        + enc(ref));
        String encoding = contents.path("encoding").asText("");
        String content = contents.path("content").asText("");
        if ("base64".equals(encoding) && !content.isBlank()) {
            return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
        }
        return "";
    }

    @Override
    public ChangeRequest openChangeRequest(GitIntegrationRow integ, ChangeRequestSpec spec) {
        String repo = "/repos/" + integ.repoOwner() + "/" + integ.repoName();

        JsonNode baseRef = get(integ, repo + "/git/ref/heads/" + enc(spec.baseBranch()));
        String baseSha = baseRef.path("object").path("sha").asText();

        // Rolling: if the head branch already exists, commit on top of IT (accumulate)
        // and fast-forward the ref; otherwise branch off the base. Non-rolling always
        // branches off the base (and POSTs a fresh ref, as before).
        JsonNode existingHead =
                spec.rolling() ? getOrNull(integ, repo + "/git/ref/heads/" + enc(spec.headBranch())) : null;
        boolean branchExists = existingHead != null;
        String parentSha =
                branchExists ? existingHead.path("object").path("sha").asText() : baseSha;

        JsonNode parentCommit = get(integ, repo + "/git/commits/" + enc(parentSha));
        String parentTreeSha = parentCommit.path("tree").path("sha").asText();

        ArrayNode treeEntries = mapper.createArrayNode();
        for (FileChange fc : spec.files()) {
            ObjectNode blobBody = mapper.createObjectNode();
            blobBody.put("content", fc.content());
            blobBody.put("encoding", "utf-8");
            JsonNode blob = post(integ, repo + "/git/blobs", blobBody);
            String blobSha = blob.path("sha").asText();

            ObjectNode entry = mapper.createObjectNode();
            entry.put("path", fc.path());
            entry.put("mode", "100644");
            entry.put("type", "blob");
            entry.put("sha", blobSha);
            treeEntries.add(entry);
        }

        ObjectNode treeBody = mapper.createObjectNode();
        treeBody.put("base_tree", parentTreeSha);
        treeBody.set("tree", treeEntries);
        JsonNode newTree = post(integ, repo + "/git/trees", treeBody);
        String newTreeSha = newTree.path("sha").asText();

        ObjectNode commitBody = mapper.createObjectNode();
        commitBody.put("message", spec.title());
        commitBody.put("tree", newTreeSha);
        ArrayNode parents = mapper.createArrayNode();
        parents.add(parentSha);
        commitBody.set("parents", parents);
        JsonNode newCommit = post(integ, repo + "/git/commits", commitBody);
        String newCommitSha = newCommit.path("sha").asText();

        if (branchExists) {
            ObjectNode patchBody = mapper.createObjectNode();
            patchBody.put("sha", newCommitSha);
            patchBody.put("force", false); // parent is the branch head → a fast-forward
            patch(integ, repo + "/git/refs/heads/" + enc(spec.headBranch()), patchBody);
        } else {
            ObjectNode refBody = mapper.createObjectNode();
            refBody.put("ref", "refs/heads/" + spec.headBranch());
            refBody.put("sha", newCommitSha);
            post(integ, repo + "/git/refs", refBody);
        }

        // Reuse an open PR for this head branch when rolling; else open a new one.
        if (spec.rolling()) {
            JsonNode open =
                    get(integ, repo + "/pulls?state=open&head=" + enc(integ.repoOwner() + ":" + spec.headBranch()));
            if (open.isArray() && !open.isEmpty()) {
                JsonNode pr = open.get(0);
                int number = pr.path("number").asInt();
                ObjectNode upd = mapper.createObjectNode();
                upd.put("title", spec.title());
                upd.put("body", spec.body());
                patch(integ, repo + "/pulls/" + number, upd);
                return new ChangeRequest(pr.path("html_url").asText(), number);
            }
        }

        ObjectNode prBody = mapper.createObjectNode();
        prBody.put("title", spec.title());
        prBody.put("head", spec.headBranch());
        prBody.put("base", spec.baseBranch());
        prBody.put("body", spec.body());
        if (spec.draft()) prBody.put("draft", true);
        JsonNode pr = post(integ, repo + "/pulls", prBody);
        return new ChangeRequest(pr.path("html_url").asText(), pr.path("number").asInt());
    }

    private JsonNode get(GitIntegrationRow integ, String path) {
        URI uri = UrlGuard.requirePublicHttp(baseUrl(integ) + path);
        HttpRequest req = baseRequest(integ, uri).GET().build();
        return send(req);
    }

    /**
     * GET that returns null ONLY on a real 404 (used to probe whether a rolling branch exists).
     * Any other non-2xx (401/403/429/5xx) is re-thrown — treating, say, a transient 5xx as
     * "branch absent" would commit off the wrong parent and break the rolling-branch update.
     */
    private JsonNode getOrNull(GitIntegrationRow integ, String path) {
        URI uri = UrlGuard.requirePublicHttp(baseUrl(integ) + path);
        HttpRequest req = baseRequest(integ, uri).GET().build();
        HttpResponse<String> res = exchange(req);
        if (res.statusCode() == 404) return null;
        return validateAndParse(res);
    }

    private JsonNode patch(GitIntegrationRow integ, String path, JsonNode body) {
        URI uri = UrlGuard.requirePublicHttp(baseUrl(integ) + path);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, e, LABEL, "request encode failed");
        }
        HttpRequest req = baseRequest(integ, uri)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        return send(req);
    }

    private JsonNode post(GitIntegrationRow integ, String path, JsonNode body) {
        URI uri = UrlGuard.requirePublicHttp(baseUrl(integ) + path);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new TessaryException(GitError.PROVIDER_CALL_FAILED, e, LABEL, "request encode failed");
        }
        HttpRequest req = baseRequest(integ, uri)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
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

    private static String encPath(String path) {
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(enc(parts[i]));
        }
        return sb.toString();
    }
}
