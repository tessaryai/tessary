// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;

/** The GitHub client over a test transport, for a Spring test outside this package. */
public final class GithubClients {

    private GithubClients() {}

    public static GithubClient over(GithubTokenService tokens, ObjectMapper mapper, HttpClient http) {
        return new GithubClient(tokens, mapper, http);
    }
}
