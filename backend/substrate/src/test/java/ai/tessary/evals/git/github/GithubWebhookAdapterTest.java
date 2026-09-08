// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.git.GitWebhookAdapter.NormalizedPushEvent;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** HMAC verification + push normalisation, no network. */
class GithubWebhookAdapterTest {

    private static final String SECRET = "s3cret-webhook-key";

    private GithubWebhookAdapter adapter() {
        GithubAppProperties props = new GithubAppProperties();
        props.setWebhookSecret(SECRET);
        return new GithubWebhookAdapter(props, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void verifiesValidSignature() throws Exception {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("x-hub-signature-256", sign(body));
        assertTrue(adapter().verifySignature(headers, body));
    }

    @Test
    void rejectsWrongSignature() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("x-hub-signature-256", "sha256=deadbeef");
        assertFalse(adapter().verifySignature(headers, body));
    }

    @Test
    void rejectsWhenSecretMissing() {
        GithubAppProperties props = new GithubAppProperties(); // no webhook secret
        assertFalse(new GithubWebhookAdapter(props, new com.fasterxml.jackson.databind.ObjectMapper())
                .verifySignature(Map.of("x-hub-signature-256", "sha256=whatever"), new byte[0]));
    }

    @Test
    void parsesPushEvent() {
        String body = """
            {"ref":"refs/heads/main","before":"aaa111","after":"bbb222",
             "repository":{"name":"tessary","owner":{"login":"tessary"}}}
            """;
        Optional<NormalizedPushEvent> ev = adapter().parsePush(Map.of("x-github-event", "push"), body);
        assertTrue(ev.isPresent());
        assertEquals("tessary", ev.get().repoOwner());
        assertEquals("tessary", ev.get().repoName());
        assertEquals("aaa111", ev.get().baseSha());
        assertEquals("bbb222", ev.get().headSha());
    }

    @Test
    void ignoresNonPushAndBranchDeletion() {
        String del = """
            {"ref":"refs/heads/x","before":"aaa","after":"0000000000000000000000000000000000000000",
             "repository":{"name":"r","owner":{"login":"o"}}}
            """;
        assertTrue(adapter().parsePush(Map.of("x-github-event", "ping"), "{}").isEmpty());
        assertTrue(adapter().parsePush(Map.of("x-github-event", "push"), del).isEmpty());
    }

    @Test
    void readsDeliveryAndEventHeaders() {
        var a = adapter();
        Map<String, String> headers = Map.of("x-github-delivery", "abc-123", "x-github-event", "push");
        assertEquals("abc-123", a.deliveryId(headers));
        assertEquals("push", a.eventType(headers));
    }
}
