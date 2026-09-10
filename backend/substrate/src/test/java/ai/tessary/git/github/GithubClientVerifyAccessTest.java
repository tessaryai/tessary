// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import org.junit.jupiter.api.Test;

/**
 * What connect-time verification tells a person when GitHub says no.
 *
 * <p>The mapping is the whole point of the method, and getting 404 wrong is the expensive one:
 * GitHub answers 404 both for a repository that does not exist and for a private one a fine-grained
 * token was never granted, so "no such repository" would be confidently wrong for the commonest
 * real failure — a correct name with an under-scoped token.
 */
class GithubClientVerifyAccessTest {

    @Test
    void unauthorized_readsAsARejectedCredential() {
        TessaryException e = GithubClient.verifyRefusal(401, "acme/web");
        assertNotNull(e);
        assertEquals(GitError.CREDENTIALS_REJECTED, e.error());
        assertTrue(e.getMessage().contains("GitHub"), "names the provider that did the rejecting");
    }

    @Test
    void forbidden_readsAsAnExplicitRefusalOnThatRepo() {
        TessaryException e = GithubClient.verifyRefusal(403, "acme/web");
        assertNotNull(e);
        assertEquals(GitError.REPO_ACCESS_DENIED, e.error());
        assertTrue(e.getMessage().contains("acme/web"));
    }

    @Test
    void notFound_coversBothReadingsRatherThanAssertingTheWrongOne() {
        TessaryException e = GithubClient.verifyRefusal(404, "acme/web");
        assertNotNull(e);
        assertEquals(GitError.REPO_UNREACHABLE, e.error());
        String msg = e.getMessage();
        assertTrue(msg.contains("acme/web"));
        // Both recovery paths have to be on screen, because the status cannot tell us which applies.
        assertTrue(msg.contains("owner and repository name"), "the name might be wrong");
        assertTrue(msg.contains("Contents: read"), "or the token might not reach it");
    }

    @Test
    void successAndOtherStatuses_areNotRefusals() {
        assertNull(GithubClient.verifyRefusal(200, "acme/web"));
        // A 500 or a 429 is a provider failure, not a verdict about access: letting it fall through
        // keeps it out of the "your token is wrong" copy, which would send someone to fix a token
        // that was fine.
        assertNull(GithubClient.verifyRefusal(500, "acme/web"));
        assertNull(GithubClient.verifyRefusal(429, "acme/web"));
    }
}
