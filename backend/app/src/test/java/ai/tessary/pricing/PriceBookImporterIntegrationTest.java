// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Acceptance for the boot-time rate import against the real Postgres. The importer has already run once
 * by the time these execute — it listens for {@code ApplicationReadyEvent}, which the test context fires —
 * so every test here is really asking what a SECOND boot does.
 */
@SpringBootTest
// Own context on purpose: it asserts what a SECOND boot of the importer does, so it must own the price-book tables
// the first boot seeded.
@TestPropertySource(properties = "test.context-group=price-book-importer")
class PriceBookImporterIntegrationTest {

    @Autowired
    PriceBookImporter importer;

    @Autowired
    PriceBookRepository books;

    @Autowired
    JdbcClient jdbc;

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    @DisplayName("re-importing the same files writes nothing — a second boot is a no-op")
    void reimport_isIdempotent() {
        long models = count("model");
        long prices = count("model_price");
        long bookRows = count("price_book");

        importer.importSnapshots();
        importer.importSnapshots();

        assertEquals(bookRows, count("price_book"), "same bytes, same version, no second book");
        assertEquals(models, count("model"), "no duplicate model rows");
        assertEquals(prices, count("model_price"), "no duplicate rate rows");
    }

    /**
     * Before any book is imported (a fresh install, or pricing switched off) nothing is priced, and the
     * coverage count the vitals card shows is zero rather than a query that cannot be written.
     * Transactional, so the books this context's other tests read come back when it ends.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void withNoBookInForce_nothingIsPricedAndTheCoverageIsZero() {
        jdbc.sql("DELETE FROM price_book").update();

        assertEquals(List.of(), books.currentBooks());
        assertEquals(0, books.pricedModelCount());
        assertTrue(books.rateFor("claude-haiku-4-5").isEmpty(), "unpriced, not free");
    }
}
