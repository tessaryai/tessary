// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.tessary.auth.AuthProvider.AuthException;
import ai.tessary.auth.AuthProvider.AuthResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The WorkOS REST client with its transport mocked: what each call sends (endpoint, credentials in
 * the body or as a bearer header), how a response parses into an {@link AuthResult}, and that every
 * transport or upstream failure surfaces as {@link AuthException}, the type callers turn into 502.
 */
@ExtendWith(MockitoExtension.class)
class WorkOsClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    HttpClient http;

    @Mock
    HttpResponse<String> response;

    private final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);

    private WorkOsClient client() {
        WorkOsProperties props = new WorkOsProperties();
        props.setApiKey("sk_test_1");
        props.setClientId("client_1");
        props.setRedirectUri("https://app.example.com/auth/callback");
        return new WorkOsClient(props, JSON, http);
    }

    private void answer(int status, String body) throws Exception {
        when(http.send(sent.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
    }

    /** The JSON object a request carried, read back off its body publisher. */
    private static Map<String, Object> bodyOf(HttpRequest req) throws Exception {
        List<ByteBuffer> chunks = new ArrayList<>();
        req.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                chunks.add(item);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : chunks) sb.append(StandardCharsets.UTF_8.decode(b));
        return JSON.readValue(sb.toString(), new TypeReference<>() {});
    }

    @ParameterizedTest
    @CsvSource({
        "a/b+c, &state=a%2Fb%2Bc",
        "'', ''",
        "'   ', ''",
    })
    void authorizationUrl_encodesEveryParameterAndOmitsABlankState(String state, String stateParam) {
        assertEquals(
                "https://api.workos.com/user_management/authorize?response_type=code&client_id=client_1"
                        + "&redirect_uri=https%3A%2F%2Fapp.example.com%2Fauth%2Fcallback&provider=authkit"
                        + stateParam,
                client().authorizationUrl(state));
    }

    @Test
    void authenticateWithCode_sendsTheSecretInTheBodyAndParsesTheSession() throws Exception {
        answer(200, """
                {"access_token":"at_1","refresh_token":"rt_1","expires_in":120,"organization_id":"org_9",
                 "user":{"id":"wos_1","email":"ada@example.com","first_name":"Ada","last_name":"Lovelace",
                         "profile_picture_url":"https://img.example/ada.png"}}""");
        Instant before = Instant.now();

        AuthResult r = client().authenticateWithCode("code_1");

        Instant after = Instant.now();
        assertFalse(r.accessTokenExpiresAt().isBefore(before.plusSeconds(120)), "expiry is now + expires_in");
        assertFalse(r.accessTokenExpiresAt().isAfter(after.plusSeconds(120)), "expiry is now + expires_in");
        assertEquals(
                new AuthResult(
                        "at_1",
                        "rt_1",
                        r.accessTokenExpiresAt(),
                        "wos_1",
                        "ada@example.com",
                        "Ada",
                        "Lovelace",
                        "https://img.example/ada.png",
                        "org_9"),
                r);
        HttpRequest req = sent.getValue();
        assertEquals(URI.create("https://api.workos.com/user_management/authenticate"), req.uri());
        assertEquals("POST", req.method());
        assertEquals(Optional.empty(), req.headers().firstValue("Authorization"), "authenticate never sends a bearer");
        assertEquals(
                Map.of(
                        "client_id", "client_1",
                        "client_secret", "sk_test_1",
                        "grant_type", "authorization_code",
                        "code", "code_1"),
                bodyOf(req));
    }

    @Test
    void refresh_scopesToTheOrgWhenOneIsHeld_andDefaultsAnAbsentExpiryToAnHour() throws Exception {
        answer(200, "{\"access_token\":\"at_2\",\"organization_id\":null,\"user\":{\"id\":\"wos_1\"}}");
        Instant before = Instant.now();

        AuthResult r = client().refresh("rt_1", "org_9");

        assertEquals(new AuthResult("at_2", null, r.accessTokenExpiresAt(), "wos_1", null, null, null, null, null), r);
        assertFalse(r.accessTokenExpiresAt().isBefore(before.plusSeconds(3600)), "no expires_in means one hour");
        assertEquals(
                Map.of(
                        "client_id", "client_1",
                        "client_secret", "sk_test_1",
                        "grant_type", "refresh_token",
                        "refresh_token", "rt_1",
                        "organization_id", "org_9"),
                bodyOf(sent.getValue()));
    }

    @Test
    void refresh_withoutAnOrgSendsNoOrgAndReadsAMissingOrgAsNull() throws Exception {
        answer(200, "{\"access_token\":\"at_3\",\"user\":{\"id\":\"wos_1\"}}");

        assertEquals(null, client().refresh("rt_1", null).organizationId());
        assertEquals(
                Map.of(
                        "client_id",
                        "client_1",
                        "client_secret",
                        "sk_test_1",
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        "rt_1"),
                bodyOf(sent.getValue()));
    }

    @Test
    void invitations_authenticateWithABearerHeaderAndEncodeTheId() throws Exception {
        answer(200, "{\"id\":\"inv_1\"}");
        WorkOsClient c = client();

        assertEquals(new AuthProvider.Invitation("inv_1"), c.createInvitation("bob@example.com"));
        HttpRequest create = sent.getValue();
        assertEquals(URI.create("https://api.workos.com/user_management/invitations"), create.uri());
        assertEquals(Optional.of("Bearer sk_test_1"), create.headers().firstValue("Authorization"));
        assertEquals(Map.of("email", "bob@example.com"), bodyOf(create));

        c.revokeInvitation("inv 1/../x");
        HttpRequest revoke = sent.getValue();
        assertEquals(
                URI.create("https://api.workos.com/user_management/invitations/inv+1%2F..%2Fx/revoke"),
                revoke.uri(),
                "the id is one encoded path segment, so it cannot climb to another endpoint");
        assertEquals(Optional.of("Bearer sk_test_1"), revoke.headers().firstValue("Authorization"));
    }

    @ParameterizedTest
    @CsvSource({"401, short upstream detail", "500, a long upstream body that is truncated before it is logged"})
    void aNonSuccessStatusIsAnAuthExceptionNamingOnlyPathAndStatus(int status, String body) throws Exception {
        answer(status, body.length() > 30 ? body.repeat(10) : body);

        AuthException e = assertThrows(AuthException.class, () -> client().createInvitation("bob@example.com"));

        assertEquals("workos /user_management/invitations returned " + status, e.getMessage());
    }

    @Test
    void anUnreachableUpstreamIsAnAuthException() throws Exception {
        IOException down = new IOException("connection refused");
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(down);

        AuthException e = assertThrows(AuthException.class, () -> client().authenticateWithCode("c"));

        assertEquals("workos call failed: connection refused", e.getMessage());
        assertSame(down, e.getCause());
    }

    @Test
    void anInterruptedCallIsAnAuthExceptionAndKeepsTheInterrupt() throws Exception {
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new InterruptedException("shutting down"));

        AuthException e = assertThrows(AuthException.class, () -> client().refresh("rt_1", null));

        assertEquals("workos call failed: shutting down", e.getMessage());
        assertTrue(Thread.interrupted(), "the interrupt flag must survive for the caller's executor");
    }

    @Test
    void anUnexpectedRuntimeFailureIsStillAnAuthException() throws Exception {
        IllegalArgumentException bad = new IllegalArgumentException("bad header");
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(bad);

        AuthException e = assertThrows(AuthException.class, () -> client().revokeInvitation("inv_1"));

        assertEquals("workos call failed: bad header", e.getMessage());
        assertSame(bad, e.getCause());
    }

    @Test
    void theCredentialRoutesAreNotSupported_andTheRedirectFlowIs() {
        WorkOsClient c = client();

        assertTrue(c.supportsRedirectFlow());
        assertEquals(
                "credential auth not supported by this provider",
                assertThrows(AuthException.class, () -> c.signupWithCredentials("a@example.com", "pw"))
                        .getMessage());
        assertEquals(
                "credential auth not supported by this provider",
                assertThrows(AuthException.class, () -> c.authenticateWithCredentials("a@example.com", "pw"))
                        .getMessage());
    }
}
