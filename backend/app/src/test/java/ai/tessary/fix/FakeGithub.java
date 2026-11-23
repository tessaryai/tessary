// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.testsupport.ScriptedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.jspecify.annotations.Nullable;

/**
 * One GitHub repository behind the REST paths the fix step calls, with state: commits, trees, branches and pull
 * requests. Like GitHub it refuses a branch that already exists and a ref update that is not a fast-forward, so a
 * force-push or a reused branch name fails the run. Every write is recorded for the test to read.
 */
final class FakeGithub extends HttpClient {

    static final String HOST = "203.0.113.40";
    static final String OWNER = "acme";
    static final String REPO = "agent";
    static final String DEFAULT_BRANCH = "main";

    private static final String BASE = "https://" + HOST;
    private static final String REPO_PATH = "/repos/" + OWNER + "/" + REPO;

    /** One write call: the method, the path under the repository, and the JSON body sent. */
    record Call(String method, String path, JsonNode body) {}

    /** @param files the paths this commit changed against its first parent */
    record Commit(String sha, String tree, List<String> parents, String message, Set<String> files) {}

    record Pr(
            int number,
            String head,
            String base,
            String title,
            String body,
            String state,
            @Nullable String mergedAt) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Call> writes = new ArrayList<>();
    private final Map<String, Commit> commits = new HashMap<>();
    private final Map<String, Set<String>> treeFiles = new HashMap<>();
    private final Map<String, byte[]> blobs = new HashMap<>();
    private final Map<String, String> blobPaths = new HashMap<>();
    private final Map<String, String> refs = new LinkedHashMap<>();
    private final Map<Integer, Pr> prs = new LinkedHashMap<>();
    private int next = 1;

    FakeGithub() {
        String tree = newSha("tree");
        treeFiles.put(tree, Set.of("README.md"));
        String root = newSha("commit");
        commits.put(root, new Commit(root, tree, List.of(), "Initial commit", Set.of("README.md")));
        refs.put(DEFAULT_BRANCH, root);
    }

    // ---- what the test reads and does --------------------------------------------------------------

    synchronized List<Call> writes() {
        return List.copyOf(writes);
    }

    synchronized List<Call> writes(String method, String pathSuffix) {
        return writes.stream()
                .filter(c -> c.method().equals(method) && c.path().endsWith(pathSuffix))
                .toList();
    }

    synchronized String head(String branch) {
        String sha = refs.get(branch);
        if (sha == null) throw new AssertionError("no branch " + branch);
        return sha;
    }

    synchronized Commit commit(String sha) {
        Commit c = commits.get(sha);
        if (c == null) throw new AssertionError("no commit " + sha);
        return c;
    }

    synchronized Pr pr(int number) {
        Pr pr = prs.get(number);
        if (pr == null) throw new AssertionError("no pull request #" + number);
        return pr;
    }

    synchronized String blobText(String path, String commitSha) {
        for (Map.Entry<String, String> e : blobPaths.entrySet()) {
            if (e.getValue().equals(commitSha + ":" + path)) {
                return new String(blobs.get(e.getKey()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no blob for " + path + " in " + commitSha);
    }

    /** A person pushes a commit that changes {@code path} on {@code branch}. */
    synchronized String push(String branch, String path, String message) {
        String parent = head(branch);
        String tree = newSha("tree");
        Set<String> files = new LinkedHashSet<>(treeFiles.get(commit(parent).tree()));
        files.add(path);
        treeFiles.put(tree, files);
        String sha = newSha("commit");
        commits.put(sha, new Commit(sha, tree, List.of(parent), message, Set.of(path)));
        refs.put(branch, sha);
        return sha;
    }

    /** A person merges the pull request: its base moves to a merge commit and the request reads as merged. */
    synchronized String merge(int number) {
        Pr pr = pr(number);
        String base = head(pr.base());
        String headSha = head(pr.head());
        Set<String> files = new LinkedHashSet<>(treeFiles.get(commit(base).tree()));
        files.addAll(treeFiles.get(commit(headSha).tree()));
        String tree = newSha("tree");
        treeFiles.put(tree, files);
        String sha = newSha("commit");
        commits.put(sha, new Commit(sha, tree, List.of(base, headSha), "Merge pull request #" + number, Set.of()));
        refs.put(pr.base(), sha);
        prs.put(number, new Pr(number, pr.head(), pr.base(), pr.title(), pr.body(), "closed", "2026-11-19T12:00:00Z"));
        return sha;
    }

    // ---- the REST paths ----------------------------------------------------------------------------

    @Override
    public synchronized <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        String uri = req.uri().toString();
        if (!uri.startsWith(BASE + REPO_PATH)) throw new AssertionError("outside the repository: " + uri);
        String rest = req.uri().getRawPath().substring(REPO_PATH.length());
        String method = req.method();
        String text = ScriptedHttpClient.body(req);
        JsonNode body = text.isEmpty() ? mapper.nullNode() : mapper.readTree(text);
        if (!"GET".equals(method)) writes.add(new Call(method, rest, body));
        HttpResponse<String> res = route(method, rest, body);
        @SuppressWarnings("unchecked")
        HttpResponse<T> typed = (HttpResponse<T>) res;
        return typed;
    }

    private HttpResponse<String> route(String method, String rest, JsonNode body) {
        if (rest.isEmpty() && "GET".equals(method)) {
            return ok(mapper.createObjectNode()
                    .put("default_branch", DEFAULT_BRANCH)
                    .set("permissions", mapper.createObjectNode().put("push", true)));
        }
        if (rest.startsWith("/branches/") && "GET".equals(method)) {
            String sha = refs.get(decode(rest.substring("/branches/".length())));
            if (sha == null) return status(404);
            ObjectNode out = mapper.createObjectNode();
            out.putObject("commit").put("sha", sha);
            return ok(out);
        }
        if ("/git/blobs".equals(rest) && "POST".equals(method)) {
            String sha = newSha("blob");
            blobs.put(sha, Base64.getDecoder().decode(body.path("content").asText()));
            return created(mapper.createObjectNode().put("sha", sha));
        }
        if (rest.startsWith("/git/commits/") && "GET".equals(method)) {
            Commit c = commits.get(decode(rest.substring("/git/commits/".length())));
            if (c == null) return status(404);
            ObjectNode out = mapper.createObjectNode().put("sha", c.sha());
            out.putObject("tree").put("sha", c.tree());
            return ok(out);
        }
        if ("/git/trees".equals(rest) && "POST".equals(method)) return createTree(body);
        if ("/git/commits".equals(rest) && "POST".equals(method)) return createCommit(body);
        if (rest.startsWith("/git/ref/heads/") && "GET".equals(method)) {
            String sha = refs.get(branchOf(rest, "/git/ref/heads/"));
            if (sha == null) return status(404);
            ObjectNode out = mapper.createObjectNode();
            out.putObject("object").put("sha", sha);
            return ok(out);
        }
        if ("/git/refs".equals(rest) && "POST".equals(method)) {
            String branch = body.path("ref").asText().substring("refs/heads/".length());
            if (refs.containsKey(branch)) return status(422);
            refs.put(branch, body.path("sha").asText());
            return created(mapper.createObjectNode().put("ref", "refs/heads/" + branch));
        }
        if (rest.startsWith("/git/refs/heads/") && "PATCH".equals(method)) {
            String branch = branchOf(rest, "/git/refs/heads/");
            String current = refs.get(branch);
            String target = body.path("sha").asText();
            if (current == null) return status(422);
            if (body.path("force").asBoolean(true) || !descends(target, current)) return status(422);
            refs.put(branch, target);
            return ok(mapper.createObjectNode().put("ref", "refs/heads/" + branch));
        }
        if (rest.startsWith("/compare/") && "GET".equals(method)) return compare(rest);
        if ("/pulls".equals(rest) && "POST".equals(method)) return createPr(body);
        if (rest.startsWith("/pulls/")) {
            int number = Integer.parseInt(rest.substring("/pulls/".length()));
            Pr pr = prs.get(number);
            if (pr == null) return status(404);
            if ("PATCH".equals(method)) {
                pr = new Pr(
                        number,
                        pr.head(),
                        pr.base(),
                        pr.title(),
                        body.path("body").asText(),
                        pr.state(),
                        pr.mergedAt());
                prs.put(number, pr);
            }
            return ok(prJson(pr));
        }
        throw new AssertionError("the fake has no " + method + " " + rest);
    }

    private HttpResponse<String> createTree(JsonNode body) {
        Set<String> base = treeFiles.get(body.path("base_tree").asText());
        if (base == null) return status(422);
        Set<String> files = new LinkedHashSet<>(base);
        String sha = newSha("tree");
        for (JsonNode e : body.path("tree")) {
            String path = e.path("path").asText();
            JsonNode blob = e.path("sha");
            if (blob.isNull()) {
                files.remove(path);
            } else {
                if (!blobs.containsKey(blob.asText())) return status(422);
                files.add(path);
                blobPaths.put(blob.asText(), sha + ":" + path);
            }
        }
        treeFiles.put(sha, files);
        return created(mapper.createObjectNode().put("sha", sha));
    }

    private HttpResponse<String> createCommit(JsonNode body) {
        String tree = body.path("tree").asText();
        if (!treeFiles.containsKey(tree)) return status(422);
        List<String> parents = new ArrayList<>();
        for (JsonNode p : body.path("parents")) {
            if (!commits.containsKey(p.asText())) return status(422);
            parents.add(p.asText());
        }
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : blobPaths.entrySet()) {
            if (e.getValue().startsWith(tree + ":")) changed.add(e.getValue().substring(tree.length() + 1));
        }
        String sha = newSha("commit");
        // Blobs are keyed by the commit from here on, so a test can read what each commit wrote.
        for (Map.Entry<String, String> e : blobPaths.entrySet()) {
            if (e.getValue().startsWith(tree + ":"))
                e.setValue(sha + ":" + e.getValue().substring(tree.length() + 1));
        }
        commits.put(sha, new Commit(sha, tree, parents, body.path("message").asText(), changed));
        return created(mapper.createObjectNode().put("sha", sha));
    }

    private HttpResponse<String> compare(String rest) {
        String[] range = decode(rest.substring("/compare/".length())).split("\\.\\.\\.");
        Set<String> files = new LinkedHashSet<>();
        String at = range[1];
        while (at != null && !at.equals(range[0])) {
            Commit c = commits.get(at);
            if (c == null) return status(404);
            files.addAll(c.files());
            at = c.parents().isEmpty() ? null : c.parents().get(0);
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("files");
        for (String f : files) list.addObject().put("filename", f);
        return ok(out);
    }

    private HttpResponse<String> createPr(JsonNode body) {
        if (body.path("draft").asBoolean(true)) throw new AssertionError("a fix pull request must not be a draft");
        String head = body.path("head").asText();
        if (!refs.containsKey(head)) return status(422);
        int number = prs.size() + 1;
        Pr pr = new Pr(
                number,
                head,
                body.path("base").asText(),
                body.path("title").asText(),
                body.path("body").asText(),
                "open",
                null);
        prs.put(number, pr);
        return created(prJson(pr));
    }

    private ObjectNode prJson(Pr pr) {
        ObjectNode out = mapper.createObjectNode()
                .put("number", pr.number())
                .put("html_url", "https://github.com/" + OWNER + "/" + REPO + "/pull/" + pr.number())
                .put("state", pr.state())
                .put("merged_at", pr.mergedAt());
        out.putObject("head")
                .put("ref", pr.head())
                .put("sha", Optional.ofNullable(refs.get(pr.head())).orElse(""));
        out.putObject("base").put("ref", pr.base());
        return out;
    }

    /** Whether {@code sha} has {@code ancestor} on its first-parent chain, which a fast-forward needs. */
    private boolean descends(String sha, String ancestor) {
        String at = sha;
        while (at != null) {
            if (at.equals(ancestor)) return true;
            Commit c = commits.get(at);
            at = c == null || c.parents().isEmpty() ? null : c.parents().get(0);
        }
        return false;
    }

    private String newSha(String kind) {
        return String.format(Locale.ROOT, "%s%036d", kind.charAt(0), next++);
    }

    private static String branchOf(String rest, String prefix) {
        List<String> segments = new ArrayList<>();
        for (String s : rest.substring(prefix.length()).split("/")) segments.add(decode(s));
        return String.join("/", segments);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> ok(JsonNode body) {
        return ScriptedHttpClient.response(200, body.toString());
    }

    private HttpResponse<String> created(JsonNode body) {
        return ScriptedHttpClient.response(201, body.toString());
    }

    private static HttpResponse<String> status(int status) {
        return ScriptedHttpClient.response(status, "{\"message\":\"refused by the fake\"}");
    }

    // ---- the rest of HttpClient ----------------------------------------------------------------------

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest req, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }
}
