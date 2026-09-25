// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.PricingProperties;
import ai.tessary.telemetry.HomeTessaryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Every way home's manifest or book can be wrong, and the one way it is right. No network, no database. */
class PriceBookFetcherTest {

    private static final byte[] BOOK = """
            {"gpt-4o": {"input_cost_per_token": 2.5e-06, "output_cost_per_token": 1e-05, "litellm_provider": "openai"},
             "dall-e-3": {"output_cost_per_image": 0.04}}
            """.getBytes(StandardCharsets.UTF_8);

    private static final String DIGEST = PriceSnapshot.sha256Hex(BOOK);

    private static final String PUBLISHED_AT = "2026-09-13T07:20:00.000Z";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PricingProperties props = new PricingProperties();
    private final HomeTessaryClient client = mock(HomeTessaryClient.class);
    private final PriceBookRepository books = mock(PriceBookRepository.class);
    private final PriceBookFetcher fetcher = new PriceBookFetcher(props, client, books, mapper);

    @BeforeEach
    void importsSucceed() {
        when(books.importBook(any(), any())).thenReturn(new PriceBookRepository.Imported(true, 1, 1));
    }

    private static HomeTessaryClient.Fetched ok(byte[] body) {
        return new HomeTessaryClient.Fetched(200, body);
    }

    private static HomeTessaryClient.Fetched ok(String body) {
        return ok(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String manifest(int schema, String digest, String url, String publishedAt) {
        return String.format(
                Locale.ROOT,
                "{\"schema\": %d, \"digest\": \"%s\", \"url\": \"%s\", \"published_at\": \"%s\"}",
                schema,
                digest,
                url,
                publishedAt);
    }

    private static String manifest() {
        return manifest(1, DIGEST, "https://home.tessary.ai/v1/pricing/" + DIGEST + ".json", PUBLISHED_AT);
    }

    private void serveManifest(String body) throws Exception {
        when(client.getBytes(eq(PriceBookFetcher.MANIFEST_PATH), anyInt())).thenReturn(ok(body));
    }

    private void serveBook(HomeTessaryClient.Fetched response) throws Exception {
        when(client.getBytes(eq(PriceBookFetcher.artifactPath(DIGEST)), anyInt()))
                .thenReturn(response);
    }

    @Test
    void importsANewBookDatedByTheManifest() throws Exception {
        serveManifest(manifest());
        serveBook(ok(BOOK));

        assertEquals(PriceBookFetcher.Outcome.IMPORTED, fetcher.refresh());

        ArgumentCaptor<PriceSnapshot> snapshot = ArgumentCaptor.forClass(PriceSnapshot.class);
        verify(books).importBook(snapshot.capture(), eq(Instant.parse(PUBLISHED_AT)));
        assertEquals(DIGEST, snapshot.getValue().digest());
        assertEquals(
                PriceBook.SOURCE_LITELLM + "-" + DIGEST.substring(0, 12),
                snapshot.getValue().version());
        assertEquals(1, snapshot.getValue().models().size(), "the per-image model prices no tokens");
    }

    @Test
    void aBookAlreadyHeldIsNeverDownloaded() throws Exception {
        serveManifest(manifest());
        when(books.hasDigest(DIGEST)).thenReturn(true);

        assertEquals(PriceBookFetcher.Outcome.ALREADY_HELD, fetcher.refresh());

        verify(client, never()).getBytes(eq(PriceBookFetcher.artifactPath(DIGEST)), anyInt());
        verify(books, never()).importBook(any(), any());
    }

    @Test
    void pricingDisabledTouchesNothing() {
        props.setEnabled(false);

        assertEquals(PriceBookFetcher.Outcome.DISABLED, fetcher.refresh());

        verifyNoInteractions(client, books);
    }

    @Test
    void anUnreachableManifestKeepsTheBookInForce() throws Exception {
        when(client.getBytes(anyString(), anyInt())).thenThrow(new IOException("connection refused"));
        assertEquals(PriceBookFetcher.Outcome.UNREACHABLE, fetcher.refresh());

        doReturn(new HomeTessaryClient.Fetched(503, new byte[0])).when(client).getBytes(anyString(), anyInt());
        assertEquals(PriceBookFetcher.Outcome.UNREACHABLE, fetcher.refresh());

        verify(books, never()).importBook(any(), any());
    }

    @Test
    void aManifestThatIsNotJsonIsIgnored() throws Exception {
        serveManifest("<html>gateway timeout</html>");
        assertEquals(PriceBookFetcher.Outcome.BAD_MANIFEST, fetcher.refresh());

        serveManifest("[]");
        assertEquals(PriceBookFetcher.Outcome.BAD_MANIFEST, fetcher.refresh());
    }

    @Test
    void aNewerManifestSchemaIsLeftForANewerBuild() throws Exception {
        serveManifest(manifest(2, DIGEST, "https://home.tessary.ai/v1/pricing/" + DIGEST + ".json", PUBLISHED_AT));

        assertEquals(PriceBookFetcher.Outcome.UNSUPPORTED_SCHEMA, fetcher.refresh());
        verify(client, never()).getBytes(eq(PriceBookFetcher.artifactPath(DIGEST)), anyInt());
    }

    @Test
    void aDigestThatIsNotASha256IsIgnored() throws Exception {
        String bad = "sha256:" + DIGEST;
        serveManifest(manifest(1, bad, "https://home.tessary.ai/v1/pricing/" + bad + ".json", PUBLISHED_AT));

        assertEquals(PriceBookFetcher.Outcome.BAD_MANIFEST, fetcher.refresh());
    }

    @Test
    void aManifestNamingAnyOtherUrlIsNeverFollowed() throws Exception {
        serveManifest(manifest(1, DIGEST, "https://example.com/v1/pricing/" + DIGEST + ".json", PUBLISHED_AT));
        assertEquals(PriceBookFetcher.Outcome.BAD_MANIFEST, fetcher.refresh());

        serveManifest(manifest(1, DIGEST, "https://home.tessary.ai/v1/pricing/other.json", PUBLISHED_AT));
        assertEquals(PriceBookFetcher.Outcome.BAD_MANIFEST, fetcher.refresh());

        verify(client, never()).getBytes(eq(PriceBookFetcher.artifactPath(DIGEST)), anyInt());
    }

    @Test
    void aManifestWithoutAPublishedInstantIsIgnored() throws Exception {
        serveManifest(manifest(1, DIGEST, "https://home.tessary.ai/v1/pricing/" + DIGEST + ".json", "yesterday"));

        assertEquals(PriceBookFetcher.Outcome.BAD_MANIFEST, fetcher.refresh());
    }

    @Test
    void aBookThatDoesNotHashToItsDigestIsRejected() throws Exception {
        serveManifest(manifest());
        byte[] tampered = new String(BOOK, StandardCharsets.UTF_8)
                .replace("2.5e-06", "2.5e-09")
                .getBytes(StandardCharsets.UTF_8);
        serveBook(ok(tampered));

        assertEquals(PriceBookFetcher.Outcome.BAD_BOOK, fetcher.refresh());
        verify(books, never()).importBook(any(), any());
    }

    @Test
    void aBookThatPricesNothingIsRejected() throws Exception {
        byte[] empty = "{\"dall-e-3\": {\"output_cost_per_image\": 0.04}}".getBytes(StandardCharsets.UTF_8);
        String digest = PriceSnapshot.sha256Hex(empty);
        serveManifest(manifest(1, digest, "https://home.tessary.ai/v1/pricing/" + digest + ".json", PUBLISHED_AT));
        when(client.getBytes(eq(PriceBookFetcher.artifactPath(digest)), anyInt()))
                .thenReturn(ok(empty));

        assertEquals(PriceBookFetcher.Outcome.BAD_BOOK, fetcher.refresh());
        verify(books, never()).importBook(any(), any());
    }

    @Test
    void aMissingOrOversizedBookKeepsTheBookInForce() throws Exception {
        serveManifest(manifest());
        serveBook(new HomeTessaryClient.Fetched(404, new byte[0]));
        assertEquals(PriceBookFetcher.Outcome.UNREACHABLE, fetcher.refresh());

        doThrow(new IOException("over the byte limit"))
                .when(client)
                .getBytes(eq(PriceBookFetcher.artifactPath(DIGEST)), anyInt());
        assertEquals(PriceBookFetcher.Outcome.UNREACHABLE, fetcher.refresh());

        verify(books, never()).importBook(any(), any());
    }

    /** A refresh interrupted mid-fetch gives up as unreachable and hands the interrupt back to its caller. */
    @Test
    void anInterruptedFetchIsUnreachableAndKeepsTheInterrupt() throws Exception {
        when(client.getBytes(anyString(), anyInt())).thenThrow(new InterruptedException("shutting down"));

        assertEquals(PriceBookFetcher.Outcome.UNREACHABLE, fetcher.refresh());
        assertTrue(Thread.interrupted(), "the interrupt is restored, not swallowed");
        verify(books, never()).importBook(any(), any());
    }
}
