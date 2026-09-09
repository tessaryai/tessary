// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

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
import org.springframework.stereotype.Component;

/** The real {@link GithubManifestExchange}: an actual HTTPS call to {@code api.github.com}. */
@Component
public class GithubManifestHttpExchange implements GithubManifestExchange {

    private final ObjectMapper mapper;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GithubManifestHttpExchange(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonNode convert(String code) {
        URI uri = UrlGuard.requirePublicHttp("https://api.github.com/app-manifests/"
                + URLEncoder.encode(code, StandardCharsets.UTF_8) + "/conversions");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, BoundedBody.string());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TessaryException(GitError.MANIFEST_CONVERSION_FAILED, e);
        }
        if (res.statusCode() / 100 != 2) {
            // A reused/expired code lands here (GitHub rejects the second conversion of a one-shot code).
            throw new TessaryException(GitError.MANIFEST_CONVERSION_FAILED);
        }
        try {
            return mapper.readTree(res.body());
        } catch (Exception e) {
            throw new TessaryException(GitError.MANIFEST_CONVERSION_FAILED, e);
        }
    }
}
