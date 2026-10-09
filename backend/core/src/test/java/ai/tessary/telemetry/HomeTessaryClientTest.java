// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The {@code x-amz-content-sha256} value home's edge checks: lowercase hex SHA-256 of the exact bytes
 * posted. A wrong encoding (uppercase, base64) is refused 403 as surely as a missing header.
 */
@ExtendWith(MockitoExtension.class)
class HomeTessaryClientTest {

    @Mock
    HttpClient http;

    @Mock
    HttpResponse<Void> response;

    /**
     * #62: home's CloudFront signs the request to its Lambda origin but does not hash a POST body, so a
     * ping without this header, or with a hash of other bytes than the ones sent, is answered 403 and no
     * heartbeat ever arrives. The digest is {@code shasum -a 256} of the UTF-8 bytes of the payload, computed
     * outside the code; the ü makes a hash of the chars (or of Latin-1 bytes) come out different.
     */
    @Test
    void postJsonSendsTheSha256OfTheExactUtf8BodyItPosts() throws Exception {
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(200);

        int status = new HomeTessaryClient(http).postJson("/v1/ping", "{\"os\":\"lin\u00fcx\"}");

        assertEquals(200, status);
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        assertEquals(
                Optional.of("edf6128bc98e616a5fb48c47c3fb2deddd40ca6231aaa7c616a84ee034cbec8c"),
                sent.getValue().headers().firstValue("x-amz-content-sha256"),
                "the edge refuses a ping whose body hash is missing or wrong");
        assertEquals(
                15L,
                sent.getValue().bodyPublisher().orElseThrow().contentLength(),
                "the hashed bytes are the posted bytes: 14 chars, 15 bytes in UTF-8");
    }

    @Mock
    HttpResponse<InputStream> streamed;

    /**
     * The bug: a body past the limit is truncated and handed back, so a caller that hashes it mistakes a
     * cut-off file for a different version. Past the limit must throw.
     */
    @Test
    void getBytesThrowsPastTheLimitRatherThanTruncating() throws Exception {
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(streamed);
        when(streamed.statusCode()).thenReturn(200);
        when(streamed.body()).thenReturn(new ByteArrayInputStream(new byte[5]));

        IOException ex = assertThrows(
                IOException.class, () -> new HomeTessaryClient(http).getBytes("/v1/pricing/manifest.json", 4));
        assertEquals("/v1/pricing/manifest.json is over the 4-byte limit", ex.getMessage());
    }

    /** The bug: an error page's body is returned as if it were the file, and gets hashed and stored. */
    @Test
    void getBytesReturnsNoBodyForANon200() throws Exception {
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(streamed);
        when(streamed.statusCode()).thenReturn(404);
        when(streamed.body()).thenReturn(new ByteArrayInputStream("not found".getBytes(StandardCharsets.UTF_8)));

        HomeTessaryClient.Fetched fetched = new HomeTessaryClient(http).getBytes("/v1/pricing/manifest.json", 1024);

        assertEquals(404, fetched.status());
        assertEquals(0, fetched.body().length);
    }

    /** The bug: a body exactly at the limit is refused, or trimmed, instead of returned whole. */
    @Test
    void getBytesReturnsABodyExactlyAtTheLimitWhole() throws Exception {
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(streamed);
        when(streamed.statusCode()).thenReturn(200);
        when(streamed.body()).thenReturn(new ByteArrayInputStream("{\"a\"}".getBytes(StandardCharsets.UTF_8)));

        HomeTessaryClient.Fetched fetched = new HomeTessaryClient(http).getBytes("/v1/pricing/manifest.json", 5);

        assertEquals(200, fetched.status());
        assertEquals("{\"a\"}", new String(fetched.body(), StandardCharsets.UTF_8));
    }
}
