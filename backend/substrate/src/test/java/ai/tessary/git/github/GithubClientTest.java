// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static ai.tessary.git.github.GithubFixtures.configuredApp;
import static ai.tessary.git.github.GithubFixtures.secretBox;
import static ai.tessary.testsupport.ScriptedHttpClient.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProviderClient.PullRequest;
import ai.tessary.git.GitProviderClient.RepoAccess;
import ai.tessary.git.GitProviderClient.TreeEntry;
import ai.tessary.git.GitProviderClient.WriteAccess;
import ai.tessary.git.GitProviderClient.WriteBlock;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.testsupport.ScriptedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The GitHub REST client over a scripted transport, authenticated by a real PAT-mode token service: what
 * connect-time verification and head resolution send, and how each answer reads. The host is a TEST-NET-3
 * address so {@code UrlGuard} passes without DNS.
 */
class GithubClientTest {

    private static final String BASE = "https://203.0.113.40";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretBox box = secretBox();
    private final ScriptedHttpClient http = new ScriptedHttpClient();
    private final GithubClient client =
            new GithubClient(new GithubTokenService(new GithubAppProperties(), box, mapper), mapper, http);

    private GitIntegrationRow repo(String name) {
        return new GitIntegrationRow(
                "i1",
                "p1",
                "github",
                "203.0.113.40",
                "acme",
                name,
                "develop",
                box.seal("{\"token\":\"ghp_x\"}"),
                "t",
                "t");
    }

    /** A readable repo reports its default branch, falling back to {@code main} when GitHub names none. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"{\"default_branch\":\"trunk\"}|trunk", "{\"default_branch\":\"\"}|main", "{}|main"})
    void verifyAccess_readsTheDefaultBranch(String body, String branch) {
        http.on(BASE + "/repos/acme/my%20repo", response(200, body));

        assertEquals(new RepoAccess(branch), client.verifyAccess(repo("my repo")));
        assertEquals(
                "Bearer ghp_x",
                http.sent().get(0).headers().firstValue("Authorization").orElseThrow(),
                "verification reads with the integration's own credential");
    }

    /**
     * A refusal keeps its access verdict through the real round trip; any other non-2xx, or a 2xx whose body
     * is not JSON, is a provider failure rather than a verdict about access. GitHub answers 404 both for a
     * repository that does not exist and for a private one a fine-grained token was never granted, so the 404
     * message carries both readings; 401 names the provider that did the rejecting, and 403 the repo it refused.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "401|{}|CREDENTIALS_REJECTED|GitHub rejected these credentials. Check the token has not expired or"
                        + " been revoked",
                "403|{}|REPO_ACCESS_DENIED|These credentials cannot read acme/web. Grant the token Contents: read on"
                        + " this repository, or enter a repository it already covers",
                "404|{}|REPO_UNREACHABLE|No repository at acme/web that these credentials can read. Check the owner"
                        + " and repository name, and that the token grants Contents: read on it",
                "500|{}|PROVIDER_CALL_FAILED|github API call failed: HTTP 500",
                "200|not json|PROVIDER_CALL_FAILED|github API call failed: non-JSON response"
            })
    void verifyAccess_anUnreadableAnswerIsTyped(int status, String body, GitError expected, String message) {
        http.on(BASE + "/repos/acme/web", response(status, body));

        TessaryException e = assertThrows(TessaryException.class, () -> client.verifyAccess(repo("web")));
        assertEquals(expected, e.error());
        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifyAccess_aTransportFailureIsAProviderFailure(boolean interrupted) {
        http.on(BASE + "/repos/acme/web", interrupted ? new InterruptedException() : new IOException("reset"));

        TessaryException e = assertThrows(TessaryException.class, () -> client.verifyAccess(repo("web")));
        assertEquals(GitError.PROVIDER_CALL_FAILED, e.error());
        assertEquals(interrupted, Thread.interrupted(), "the interrupt flag is restored for the caller");
    }

    @Test
    void resolveHeadSha_encodesTheBranchIntoOnePathSegment() {
        http.on(BASE + "/repos/acme/web/branches/feature%2Fx%20y", response(200, "{\"commit\":{\"sha\":\"abc123\"}}"));

        assertEquals("abc123", client.resolveHeadSha(repo("web"), "feature/x y"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void resolveHeadSha_withoutABranchUsesTheIntegrationDefault(String branch) {
        http.on(BASE + "/repos/acme/web/branches/develop", response(200, "{\"commit\":{\"sha\":\"def456\"}}"));

        assertEquals("def456", client.resolveHeadSha(repo("web"), branch));
    }

    // ---- write access ----------------------------------------------------------------------------

    private static final String MINT = BASE + "/app/installations/77/access_tokens";

    private GitIntegrationRow appRepo() {
        return new GitIntegrationRow(
                "i9",
                "p1",
                "github",
                "203.0.113.40",
                "acme",
                "web",
                "main",
                box.seal("{\"installationId\":77}"),
                "t",
                "t");
    }

    private GithubClient appClient() {
        return new GithubClient(new GithubTokenService(configuredApp(), box, mapper, http, BASE, BASE), mapper, http);
    }

    private static HttpResponse<String> minted(String token, String permissions) {
        return response(
                201,
                "{\"token\":\"" + token + "\",\"expires_at\":\"" + Instant.now().plusSeconds(3600)
                        + "\",\"permissions\":" + permissions + "}");
    }

    /** A PAT's write check reads {@code permissions.push} on the repository. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"{\"permissions\":{\"push\":true}}|true", "{\"permissions\":{\"push\":false}}|false", "{}|false"})
    void writeAccess_aPatReadsPushOnTheRepository(String body, boolean canWrite) {
        http.on(BASE + "/repos/acme/web", response(200, body));

        WriteAccess access = client.writeAccess(repo("web"));

        assertEquals(canWrite ? WriteAccess.granted() : WriteAccess.blocked(WriteBlock.TOKEN_CANNOT_PUSH), access);
    }

    @Test
    void writeAccess_aRejectedPatIsTyped() {
        http.on(BASE + "/repos/acme/web", response(401, "{}"));

        TessaryException e = assertThrows(TessaryException.class, () -> client.writeAccess(repo("web")));
        assertEquals(GitError.CREDENTIALS_REJECTED, e.error());
    }

    /** An App's write check needs both grants on the installation token; no repository read is made. */
    @Test
    void writeAccess_anAppWithBothGrantsCanWrite() {
        http.on(MINT, minted("ghs_1", "{\"contents\":\"write\",\"pull_requests\":\"write\",\"metadata\":\"read\"}"));

        assertEquals(WriteAccess.granted(), appClient().writeAccess(appRepo()));
        assertEquals(1, http.sent().size(), "the permissions come with the token");
    }

    /**
     * A read-only answer is checked once more with a new token, so an owner who just accepted the new
     * permissions is not told "no" for the life of the cached token.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"contents\":\"write\",\"pull_requests\":\"write\"}|true",
                "{\"contents\":\"write\"}|false",
                "{\"contents\":\"read\",\"pull_requests\":\"write\"}|false"
            })
    void writeAccess_anAppWithoutWriteAsksAgainWithANewToken(String afterRefresh, boolean canWrite) {
        http.on(MINT, minted("ghs_1", "{\"contents\":\"read\"}"), minted("ghs_2", afterRefresh));

        WriteAccess access = appClient().writeAccess(appRepo());

        assertEquals(canWrite ? WriteAccess.granted() : WriteAccess.blocked(WriteBlock.APP_PERMISSION_MISSING), access);
        assertEquals(2, http.sent().size());
    }

    // ---- commits and refs ------------------------------------------------------------------------

    private JsonNode sentJson(int i) throws Exception {
        return mapper.readTree(ScriptedHttpClient.body(http.sent().get(i)));
    }

    @Test
    void createBlob_sendsBase64AndReturnsTheSha() throws Exception {
        http.on(BASE + "/repos/acme/web/git/blobs", response(201, "{\"sha\":\"b1\"}"));

        assertEquals("b1", client.createBlob(repo("web"), "hé\n".getBytes(StandardCharsets.UTF_8)));

        HttpRequest req = http.sent().get(0);
        assertEquals("POST", req.method());
        assertEquals(
                "application/json", req.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("Bearer ghp_x", req.headers().firstValue("Authorization").orElseThrow());
        JsonNode body = sentJson(0);
        assertEquals("base64", body.path("encoding").asText());
        assertEquals(
                "hé\n",
                new String(Base64.getDecoder().decode(body.path("content").asText()), StandardCharsets.UTF_8));
    }

    @Test
    void commitTreeSha_readsTheCommitsTree() {
        http.on(BASE + "/repos/acme/web/git/commits/c1", response(200, "{\"tree\":{\"sha\":\"t1\"}}"));

        assertEquals("t1", client.commitTreeSha(repo("web"), "c1"));
    }

    /** A deletion is an explicit null sha: an absent sha would not delete the path. */
    @Test
    void createTree_writesOverTheBaseTree_andDeletesWithAnExplicitNullSha() throws Exception {
        http.on(BASE + "/repos/acme/web/git/trees", response(201, "{\"sha\":\"t2\"}"));

        String sha = client.createTree(
                repo("web"),
                "t1",
                List.of(new TreeEntry("src/a.py", "100644", "b1"), new TreeEntry("old.txt", "100644", null)));

        assertEquals("t2", sha);
        JsonNode body = sentJson(0);
        assertEquals("t1", body.path("base_tree").asText());
        JsonNode write = body.path("tree").get(0);
        assertEquals("src/a.py", write.path("path").asText());
        assertEquals("100644", write.path("mode").asText());
        assertEquals("blob", write.path("type").asText());
        assertEquals("b1", write.path("sha").asText());
        JsonNode delete = body.path("tree").get(1);
        assertTrue(delete.has("sha") && delete.path("sha").isNull(), "sha is present and null");
    }

    @Test
    void createCommit_hasOneParent() throws Exception {
        http.on(BASE + "/repos/acme/web/git/commits", response(201, "{\"sha\":\"c2\"}"));

        assertEquals("c2", client.createCommit(repo("web"), "Fix the retry", "t2", "c1"));

        JsonNode body = sentJson(0);
        assertEquals("Fix the retry", body.path("message").asText());
        assertEquals("t2", body.path("tree").asText());
        assertEquals("[\"c1\"]", body.path("parents").toString());
    }

    /** Inside a refs path a branch keeps its slashes; each segment is encoded. */
    @Test
    void branchSha_readsTheRef_andAMissingBranchIsEmpty() {
        http.on(BASE + "/repos/acme/web/git/ref/heads/tessary/fix%231", response(200, "{\"object\":{\"sha\":\"c1\"}}"))
                .on(BASE + "/repos/acme/web/git/ref/heads/gone", response(404, "{}"));

        assertEquals(Optional.of("c1"), client.branchSha(repo("web"), "tessary/fix#1"));
        assertEquals(Optional.empty(), client.branchSha(repo("web"), "gone"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", " ", "a/../b", "../x", "/a", "a/", "a//b", ".hidden", "a/.b", "a.lock", "a~1", "a:b", "a b"})
    void branchSha_refusesANameThatIsNotABranch(String branch) {
        assertThrows(IllegalArgumentException.class, () -> client.branchSha(repo("web"), branch));
        assertTrue(http.sent().isEmpty(), "nothing is sent");
    }

    @Test
    void createBranch_postsTheFullRef() throws Exception {
        http.on(BASE + "/repos/acme/web/git/refs", response(201, "{}"));

        client.createBranch(repo("web"), "tessary/case-1", "c2");

        JsonNode body = sentJson(0);
        assertEquals("refs/heads/tessary/case-1", body.path("ref").asText());
        assertEquals("c2", body.path("sha").asText());
    }

    @Test
    void createBranch_aBranchThatAlreadyExistsIsBranchMoved() {
        http.on(BASE + "/repos/acme/web/git/refs", response(422, "{\"message\":\"Reference already exists\"}"));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client.createBranch(repo("web"), "tessary/case-1", "c2"));
        assertEquals(GitError.BRANCH_MOVED, e.error());
        assertEquals("Branch tessary/case-1 on acme/web changed while Tessary was writing to it", e.getMessage());
    }

    /** The update says force: false out loud, so a push that landed first is never overwritten. */
    @Test
    void updateBranch_isAFastForwardOnly() throws Exception {
        http.on(BASE + "/repos/acme/web/git/refs/heads/tessary/case-1", response(200, "{}"), response(422, "{}"));

        client.updateBranch(repo("web"), "tessary/case-1", "c3");
        HttpRequest req = http.sent().get(0);
        assertEquals("PATCH", req.method());
        JsonNode body = sentJson(0);
        assertEquals("c3", body.path("sha").asText());
        assertTrue(body.has("force") && !body.path("force").asBoolean(true));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client.updateBranch(repo("web"), "tessary/case-1", "c4"));
        assertEquals(GitError.BRANCH_MOVED, e.error());
    }

    @Test
    void changedPaths_listsEveryFile_andARenameUnderBothNames() {
        http.on(BASE + "/repos/acme/web/compare/c1...c3", response(200, """
                {"files":[
                  {"filename":"a.py"},
                  {"filename":"new.py","previous_filename":"old.py"},
                  {"filename":"a.py"}
                ]}"""));

        assertEquals(List.of("a.py", "new.py", "old.py"), client.changedPaths(repo("web"), "c1", "c3"));
    }

    /**
     * A write refused for a missing grant names what to grant. A rate limit also answers 403, so GitHub's
     * rate-limit headers make it a provider failure instead, like 429.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NULL",
            value = {
                "401|NULL|NULL|CREDENTIALS_REJECTED",
                "403|NULL|NULL|WRITE_ACCESS_MISSING",
                "403|x-ratelimit-remaining|0|PROVIDER_CALL_FAILED",
                "403|x-ratelimit-remaining|12|WRITE_ACCESS_MISSING",
                "403|retry-after|60|PROVIDER_CALL_FAILED",
                "429|NULL|NULL|PROVIDER_CALL_FAILED",
                "500|NULL|NULL|PROVIDER_CALL_FAILED",
                "422|NULL|NULL|PROVIDER_CALL_FAILED"
            })
    void aRefusedWriteIsTyped(int status, String header, String value, GitError expected) {
        Map<String, List<String>> headers = header == null ? Map.of() : Map.of(header, List.of(value));
        http.on(BASE + "/repos/acme/web/git/blobs", response(status, "{}", headers));

        TessaryException e = assertThrows(TessaryException.class, () -> client.createBlob(repo("web"), new byte[] {1}));
        assertEquals(expected, e.error());
        if (expected == GitError.WRITE_ACCESS_MISSING) {
            assertEquals(
                    "These credentials cannot write to acme/web. Grant Contents: write and Pull requests: write on"
                            + " this repository",
                    e.getMessage());
        }
    }

    // ---- pull requests ---------------------------------------------------------------------------

    private static final String PR_JSON = """
            {"number":7,"html_url":"https://github.com/acme/web/pull/7","state":"%s","merged_at":%s,
             "head":{"ref":"tessary/case-1","sha":"c3"},"base":{"ref":"main"}}""";

    private static String prJson(String state, String mergedAt) {
        return String.format(Locale.ROOT, PR_JSON, state, mergedAt);
    }

    @Test
    void createPullRequest_opensItReadyForReview() throws Exception {
        http.on(BASE + "/repos/acme/web/pulls", response(201, prJson("open", "null")));

        PullRequest pr = client.createPullRequest(repo("web"), "tessary/case-1", "main", "Fix the retry", "Why");

        assertEquals(
                new PullRequest(7, "https://github.com/acme/web/pull/7", "open", false, "tessary/case-1", "c3", "main"),
                pr);
        assertTrue(pr.open());
        JsonNode body = sentJson(0);
        assertEquals("tessary/case-1", body.path("head").asText());
        assertEquals("main", body.path("base").asText());
        assertEquals("Fix the retry", body.path("title").asText());
        assertEquals("Why", body.path("body").asText());
        assertTrue(body.has("draft") && !body.path("draft").asBoolean(true), "never a draft");
    }

    /** Merged reads from {@code merged_at}; closed without it is a reviewer's no. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "open|null|true|false|false",
                "closed|\"2026-11-01T00:00:00Z\"|false|true|false",
                "closed|null|false|false|true"
            })
    void findPullRequest_readsTheNewestInAnyState(
            String state, String mergedAt, boolean open, boolean merged, boolean rejected) {
        http.on(
                BASE + "/repos/acme/web/pulls?head=acme%3Atessary%2Fcase-1&state=all&sort=created&direction=desc"
                        + "&per_page=1",
                response(200, "[" + prJson(state, mergedAt) + "]"));

        PullRequest pr = client.findPullRequest(repo("web"), "tessary/case-1").orElseThrow();

        assertEquals(open, pr.open());
        assertEquals(merged, pr.merged());
        assertEquals(rejected, pr.rejected());
    }

    @Test
    void findPullRequest_noneIsEmpty() {
        http.on(
                BASE + "/repos/acme/web/pulls?head=acme%3Ax&state=all&sort=created&direction=desc&per_page=1",
                response(200, "[]"));

        assertEquals(Optional.empty(), client.findPullRequest(repo("web"), "x"));
    }

    @Test
    void pullRequest_readsOne_andAMissingOneIsPrNotFound() {
        http.on(BASE + "/repos/acme/web/pulls/7", response(200, prJson("closed", "\"2026-11-01T00:00:00Z\"")))
                .on(BASE + "/repos/acme/web/pulls/8", response(404, "{}"));

        assertTrue(client.pullRequest(repo("web"), 7).merged());
        TessaryException e = assertThrows(TessaryException.class, () -> client.pullRequest(repo("web"), 8));
        assertEquals(GitError.PR_NOT_FOUND, e.error());
        assertEquals("No pull request #8 on acme/web", e.getMessage());
    }

    @Test
    void updatePullRequestBody_patchesOnlyTheBody() throws Exception {
        http.on(BASE + "/repos/acme/web/pulls/7", response(200, "{}"))
                .on(BASE + "/repos/acme/web/pulls/8", response(404, "{}"));

        client.updatePullRequestBody(repo("web"), 7, "New body");
        assertEquals("PATCH", http.sent().get(0).method());
        assertEquals(
                "{\"body\":\"New body\"}", ScriptedHttpClient.body(http.sent().get(0)));

        TessaryException e =
                assertThrows(TessaryException.class, () -> client.updatePullRequestBody(repo("web"), 8, "x"));
        assertEquals(GitError.PR_NOT_FOUND, e.error());
    }
}
