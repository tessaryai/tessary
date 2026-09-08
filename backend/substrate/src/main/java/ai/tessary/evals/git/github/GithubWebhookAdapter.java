// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import ai.tessary.evals.git.GitProvider;
import ai.tessary.evals.git.GitWebhookAdapter;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.GitError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GithubWebhookAdapter implements GitWebhookAdapter {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookAdapter.class);
    private static final String LABEL = "github";
    private static final String SIG_HEADER = "x-hub-signature-256";
    private static final String DELIVERY_HEADER = "x-github-delivery";
    private static final String EVENT_HEADER = "x-github-event";
    private static final String ALL_ZEROS = "0000000000000000000000000000000000000000";

    private final GithubAppProperties props;
    private final ObjectMapper mapper;

    public GithubWebhookAdapter(GithubAppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public GitProvider provider() {
        return GitProvider.GITHUB;
    }

    @Override
    public String webhookSecret() {
        return props.getWebhookSecret();
    }

    @Override
    public boolean verifySignature(Map<String, String> headers, byte[] rawBody) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return false;
        }
        String provided = header(headers, SIG_HEADER);
        if (provided == null || provided.isBlank()) {
            return false;
        }
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody);
            expected = "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("github webhook hmac computation failed");
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String deliveryId(Map<String, String> headers) {
        return header(headers, DELIVERY_HEADER);
    }

    @Override
    public String eventType(Map<String, String> headers) {
        return header(headers, EVENT_HEADER);
    }

    @Override
    public Optional<NormalizedPushEvent> parsePush(Map<String, String> headers, String rawBody) {
        if (!"push".equals(eventType(headers))) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw new EvalsException(GitError.PROVIDER_CALL_FAILED, e, LABEL, "unparseable push payload");
        }
        JsonNode owner = root.path("repository").path("owner");
        String repoOwner = owner.path("login").asText(null);
        if (repoOwner == null || repoOwner.isBlank()) {
            repoOwner = owner.path("name").asText(null);
        }
        String repoName = root.path("repository").path("name").asText(null);
        String ref = root.path("ref").asText(null);
        String baseSha = root.path("before").asText(null);
        String headSha = root.path("after").asText(null);
        if (headSha == null || ALL_ZEROS.equals(headSha)) {
            return Optional.empty();
        }
        return Optional.of(new NormalizedPushEvent(repoOwner, repoName, ref, baseSha, headSha));
    }

    private static String header(Map<String, String> headers, String lowerKey) {
        if (headers == null) return null;
        String direct = headers.get(lowerKey);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lowerKey)) {
                return e.getValue();
            }
        }
        return null;
    }
}
