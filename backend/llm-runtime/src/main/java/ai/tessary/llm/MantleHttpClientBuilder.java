// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Hands {@code OpenAiResponsesChatModel} a JDK HTTP client wrapped in {@link MantleHttpClient}.
 *
 * <p>This is the only way to reach the mantle endpoint: the Responses builder takes an
 * {@link HttpClientBuilder} but has no hook for per-request auth, so the signing has to be the client
 * it is given.
 *
 * <p>It is also the only way to bound a hung socket on this path. Unlike
 * {@code OpenAiChatModel.builder()}, the Responses builder exposes no {@code timeout()} — so without
 * a client configured here, the 5-minute per-call ceiling every other LLM path enforces would
 * silently not apply, and one stalled mantle call would pin a grading thread indefinitely.
 */
final class MantleHttpClientBuilder implements HttpClientBuilder {

    private final AwsCredentialsProvider credentials;
    private final String region;
    private final String projectId;

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout;

    MantleHttpClientBuilder(AwsCredentialsProvider credentials, String region, String projectId, Duration readTimeout) {
        this.credentials = credentials;
        this.region = region;
        this.projectId = projectId;
        this.readTimeout = readTimeout;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    @Override
    public HttpClient build() {
        HttpClient delegate = new JdkHttpClientBuilder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
        return new MantleHttpClient(delegate, credentials, region, projectId);
    }
}
