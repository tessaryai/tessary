// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Acceptance for the boot-time rate import against the real Postgres. The importer has already run once
 * by the time these execute — it listens for {@code ApplicationReadyEvent}, which the test context fires —
 * so every test here is really asking what a SECOND boot does.
 */
@SpringBootTest
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

    private String versionOf(String source) {
        return books.currentBooks().stream()
                .filter(b -> b.source().equals(source))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no book in force for source " + source))
                .version();
    }

    @Test
    @DisplayName("boot imports the vendored book")
    void boot_importsTheVendoredBook() {
        List<PriceBook> current = books.currentBooks();
        assertEquals(1, current.size(), "one book in force per source: " + current);
        assertEquals(PriceBook.SOURCE_LITELLM, current.get(0).source());
        assertTrue(count("model") > 1000, "every snapshot key becomes a model row");
        assertTrue(count("model_price") > 1000, "and every one of them gets a rate row");
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

    @Test
    @DisplayName("a model resolves to the vendored book's own rate")
    void rateFor_resolvesFromTheVendoredBook() {
        // The mantle route-prefixed spelling BedrockModelProfile.MANTLE_ROUTE_PREFIX reports
        // resolves without any override, straight off the imported vendored book.
        ModelRate mantle = books.rateFor("bedrock_mantle/openai.gpt-5.6-luna").orElseThrow();
        assertEquals(versionOf(PriceBook.SOURCE_LITELLM), mantle.priceBookVersion());
        assertEquals(
                0, new BigDecimal("0.22").compareTo(requireRate(mantle.rates().inputPerMtok())));

        ModelRate sonnet = books.rateFor("claude-sonnet-4-6").orElseThrow();
        assertEquals(versionOf(PriceBook.SOURCE_LITELLM), sonnet.priceBookVersion());
        assertEquals(0, new BigDecimal("3").compareTo(requireRate(sonnet.rates().inputPerMtok())));
    }

    private static BigDecimal requireRate(@Nullable BigDecimal rate) {
        assertNotNull(rate, "expected a rate");
        return rate;
    }
}
