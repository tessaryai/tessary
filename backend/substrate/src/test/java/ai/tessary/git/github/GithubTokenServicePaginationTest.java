// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The GitHub {@code Link} rel="next" cursor parser that drives list-endpoint pagination. */
class GithubTokenServicePaginationTest {

    @Test
    void absentHeader_yieldsNull() {
        assertNull(GithubTokenService.nextLink(null));
        assertNull(GithubTokenService.nextLink(""));
        assertNull(GithubTokenService.nextLink("   "));
    }

    @Test
    void extractsNextUrl() {
        String header = "<https://api.github.com/user/installations?per_page=100&page=2>; rel=\"next\", "
                + "<https://api.github.com/user/installations?per_page=100&page=5>; rel=\"last\"";
        assertEquals(
                "https://api.github.com/user/installations?per_page=100&page=2", GithubTokenService.nextLink(header));
    }

    @Test
    void findsNextEvenWhenNotFirstSegment() {
        String header = "<https://api.github.com/x?page=1>; rel=\"prev\", "
                + "<https://api.github.com/x?page=3>; rel=\"next\", "
                + "<https://api.github.com/x?page=9>; rel=\"last\"";
        assertEquals("https://api.github.com/x?page=3", GithubTokenService.nextLink(header));
    }

    @Test
    void lastPage_hasNoNext() {
        String header = "<https://api.github.com/x?page=1>; rel=\"prev\", "
                + "<https://api.github.com/x?page=1>; rel=\"first\"";
        assertNull(GithubTokenService.nextLink(header));
    }
}
