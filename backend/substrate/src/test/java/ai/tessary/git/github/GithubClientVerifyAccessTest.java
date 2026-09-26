// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
