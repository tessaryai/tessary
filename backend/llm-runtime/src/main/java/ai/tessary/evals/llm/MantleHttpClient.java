// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;

/**
 * SigV4-signs every request langchain4j's OpenAI client makes to the {@code bedrock-mantle} endpoint.
 *
 * <p><b>Why this exists.</b> Mantle speaks the OpenAI wire, so the model is built with
 * {@code OpenAiResponsesChatModel} rather than the AWS SDK's {@code BedrockRuntimeClient} — and that
 * client is a plain HTTP client with no notion of AWS auth. Everywhere else in this codebase SigV4
 * happens inside the SDK; here it has to happen in the transport. Doing it as a langchain4j
 * {@link HttpClient} decorator keeps every other layer unaware: {@code ChatModelFactory} builds a
 * model, and the model's requests are signed on the way out.
 *
 * <p><b>The identity is the same one {@code bedrock-runtime} uses</b> — the ambient credentials
 * provider (the EC2 instance role in production, {@code AWS_*} env or {@code ~/.aws} locally) for the
 * platform-funded lanes, or a customer's sealed static keys for a pinned run. Only two inputs to the
 * signature differ from the runtime path: the service name is {@code bedrock-mantle}, not
 * {@code bedrock}, and the region is mantle's own rather than the ambient one. A wrong service name
 * fails as a 403 that reads like a missing permission, which is why it is a named constant here and
 * asserted by {@code scripts/probe_mantle_capabilities.py}.
 *
 * <p><b>The {@code Authorization} header is replaced, not added to.</b> langchain4j requires an
 * {@code apiKey} on the builder and unconditionally sets a bearer header from it; the signed headers
 * overwrite that. {@code ChatModelFactory} therefore passes a placeholder, and there is no Bedrock
 * API key anywhere in the deployment.
 */
final class MantleHttpClient implements HttpClient {

    /** The SigV4 service name mantle authenticates against — NOT {@code bedrock}. */
    static final String SIGNING_SERVICE = "bedrock-mantle";

    /** Attributes inference to a Bedrock Project; absent means the account's {@code default} project. */
    private static final String PROJECT_HEADER = "OpenAI-Project";

    /**
     * Headers {@code java.net.http.HttpClient} manages itself and throws on if a caller sets them.
     *
     * <p>This matters because SigV4 <b>signs {@code host}</b> — it is in {@code SignedHeaders}, so the
     * signature depends on it — while the JDK client rejects it outright ("restricted header name:
     * Host"). Dropping it from the outgoing request is nonetheless safe, and only because both sides
     * derive it from the same place: the signer takes {@code host} from the request URI, and the JDK
     * sets exactly that value from the same URI. The header the server canonicalises is therefore
     * byte-identical to the one that was signed.
     *
     * <p>The rest are listed for completeness rather than because the signer emits them today —
     * {@code content-length} in particular would be added if we ever pre-set it on the unsigned
     * request, and would fail the same way.
     */
    private static final Set<String> JDK_RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient delegate;
    private final AwsCredentialsProvider credentials;
    private final AwsV4HttpSigner signer;
    private final String region;
    private final String projectId;

    MantleHttpClient(HttpClient delegate, AwsCredentialsProvider credentials, String region, String projectId) {
        this.delegate = delegate;
        this.credentials = credentials;
        this.signer = AwsV4HttpSigner.create();
        this.region = region;
        this.projectId = projectId;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(sign(request));
    }

    /**
     * The streaming overload signs too. Nothing streams today — the judge is request/response — but an
     * unsigned SSE path would be a 403 that only appears the first time someone enables streaming,
     * long after this class was last read.
     */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(sign(request), parser, listener);
    }

    /**
     * Re-emit {@code request} carrying a SigV4 signature over its method, URI, headers and body.
     *
     * <p>The signature covers the body, so the payload handed to the signer must be byte-identical to
     * the one that goes on the wire — hence encoding once here and reusing the same string. The
     * project header is added <i>before</i> signing so it is covered rather than tacked on afterwards,
     * which would either be ignored or invalidate the signature depending on how the endpoint canon-
     * icalises headers.
     */
    private HttpRequest sign(HttpRequest request) {
        byte[] body = request.body() == null ? new byte[0] : request.body().getBytes(StandardCharsets.UTF_8);

        SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder()
                .uri(URI.create(request.url()))
                .method(SdkHttpMethod.fromValue(request.method().name()));
        for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            // Drop the placeholder bearer: signing it would bind the signature to a value the signed
            // Authorization header then replaces, and Bedrock would reject the mismatch.
            if (!"authorization".equalsIgnoreCase(header.getKey())) {
                unsigned.putHeader(header.getKey(), header.getValue());
            }
        }
        if (!projectId.isEmpty()) {
            unsigned.putHeader(PROJECT_HEADER, projectId);
        }

        SdkHttpRequest signed = signer.sign(r -> r.identity(credentials.resolveCredentials())
                        .request(unsigned.build())
                        .payload(ContentStreamProvider.fromByteArray(body))
                        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, SIGNING_SERVICE)
                        .putProperty(AwsV4HttpSigner.REGION_NAME, region))
                .request();

        Map<String, List<String>> sendable = new LinkedHashMap<>();
        signed.headers().forEach((name, values) -> {
            if (!JDK_RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                sendable.put(name, values);
            }
        });

        HttpRequest.Builder out = HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(sendable);
        return body.length == 0 ? out.build() : out.body(request.body()).build();
    }
}
