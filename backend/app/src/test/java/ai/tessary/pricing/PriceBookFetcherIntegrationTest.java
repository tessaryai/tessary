// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.config.PricingProperties;
import ai.tessary.telemetry.HomeTessaryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Which book prices new spans once books arrive both bundled in the jar and fetched from home.tessary.ai,
 * against the real Postgres. home is a mock client; everything below it is real.
 */
@SpringBootTest
// Own context on purpose: these tests put books in force, and price_book is boot-seeded reference data that
// every other class sharing a context would otherwise price against.
@TestPropertySource(properties = "test.context-group=price-book-fetcher")
class PriceBookFetcherIntegrationTest {

    @Autowired
    PriceBookRepository books;

    @Autowired
    PriceBookImporter importer;

    @Autowired
    PricingProperties props;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcClient jdbc;

    private final HomeTessaryClient home = mock(HomeTessaryClient.class);

    /** A book pricing one model no other book names, so which book priced it is unambiguous. */
    private record Book(String model, byte[] bytes, String digest) {}

    private static Book book() {
        String model = "fetch-test-" + UUID.randomUUID();
        byte[] bytes = ("{\"" + model + "\": {\"input_cost_per_token\": 1e-06, \"output_cost_per_token\": 2e-06}}")
                .getBytes(StandardCharsets.UTF_8);
        return new Book(model, bytes, PriceSnapshot.sha256Hex(bytes));
    }

    /** Serve {@code book} as home's current book, published at {@code publishedAt}, and fetch it. */
    private PriceBookFetcher.Outcome fetch(Book book, Instant publishedAt) throws Exception {
        String manifest = String.format(
                Locale.ROOT,
                "{\"schema\": 1, \"digest\": \"%s\", \"url\": \"https://home.tessary.ai/v1/pricing/%s.json\", \"published_at\": \"%s\"}",
                book.digest(),
                book.digest(),
                publishedAt);
        when(home.getBytes(eq(PriceBookFetcher.MANIFEST_PATH), anyInt()))
                .thenReturn(new HomeTessaryClient.Fetched(200, manifest.getBytes(StandardCharsets.UTF_8)));
        when(home.getBytes(eq(PriceBookFetcher.artifactPath(book.digest())), anyInt()))
                .thenReturn(new HomeTessaryClient.Fetched(200, book.bytes()));
        return new PriceBookFetcher(props, home, books, mapper).refresh();
    }

    private String inForce() {
        return books.currentBooks().stream()
                .filter(b -> b.source().equals(PriceBook.SOURCE_LITELLM))
                .findFirst()
                .orElseThrow()
                .version();
    }

    private static String versionOf(Book book) {
        return PriceBook.SOURCE_LITELLM + "-" + book.digest().substring(0, 12);
    }

    @Test
    @DisplayName("a book home published after the build prices new spans, and a restart does not undo it")
    void newerFetchedBook_winsAndSurvivesARestart() throws Exception {
        Book newer = book();

        assertEquals(
                PriceBookFetcher.Outcome.IMPORTED, fetch(newer, Instant.now().plus(Duration.ofDays(1))));

        assertEquals(versionOf(newer), inForce());
        assertEquals(
                versionOf(newer), books.rateFor(newer.model()).orElseThrow().priceBookVersion());
        assertEquals(newer.digest(), books.currentDigest(PriceBook.SOURCE_LITELLM));

        importer.importSnapshots(); // what a reboot of the same jar does
        assertEquals(versionOf(newer), inForce(), "the bundled book is dated by the build, which came first");
    }

    @Test
    @DisplayName("a book home published before a newer book is imported but never put in force")
    void olderFetchedBook_isKeptButDoesNotWin() throws Exception {
        Book older = book();

        assertEquals(PriceBookFetcher.Outcome.IMPORTED, fetch(older, Instant.parse("2020-01-01T00:00:00Z")));

        assertTrue(books.hasDigest(older.digest()));
        assertNotEquals(versionOf(older), inForce());
    }

    @Test
    @DisplayName("home serving the same bytes the jar carries is recognised by digest, never downloaded")
    void bundledBytes_areAlreadyHeld() throws Exception {
        byte[] bundled = new ClassPathResource(PriceSnapshot.LITELLM_RESOURCE).getContentAsByteArray();
        String digest = PriceSnapshot.sha256Hex(bundled);
        importer.importSnapshots();

        PriceBookFetcher.Outcome outcome = fetch(new Book("unused", bundled, digest), Instant.now());

        assertEquals(PriceBookFetcher.Outcome.ALREADY_HELD, outcome);
        verify(home, never()).getBytes(eq(PriceBookFetcher.artifactPath(digest)), anyInt());
    }

    @Test
    @DisplayName("a boot fills the bundled book's missing digest and moves a later import date back to the build")
    void importer_reconcilesABookAnEarlierBootImported() throws Exception {
        byte[] bundled = new ClassPathResource(PriceSnapshot.LITELLM_RESOURCE).getContentAsByteArray();
        String version = PriceBook.SOURCE_LITELLM + "-"
                + PriceSnapshot.sha256Hex(bundled).substring(0, 12);
        importer.importSnapshots();
        // What a book imported before this change looks like: no digest, dated by when that boot ran.
        jdbc.sql("UPDATE price_book SET digest = NULL, published_at = :later WHERE version = :version")
                .param("later", java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(3650))))
                .param("version", version)
                .update();

        importer.importSnapshots();

        String digest = jdbc.sql("SELECT digest FROM price_book WHERE version = :version")
                .param("version", version)
                .query(String.class)
                .single();
        Instant publishedAt = jdbc.sql("SELECT published_at FROM price_book WHERE version = :version")
                .param("version", version)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        assertEquals(PriceSnapshot.sha256Hex(bundled), digest);
        assertEquals(importer.bundledPublishedAt().getEpochSecond(), publishedAt.getEpochSecond(), 5);
    }
}
