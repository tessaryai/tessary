// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ModelResolverTest {

    /** A clock the test moves by hand. */
    private static final class StepClock extends Clock {
        private Instant now;

        StepClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A price-book store answering from fields, counting the rate reads the memo exists to save. */
    private static final class Books extends PriceBookRepository {
        List<PriceBook> inForce = List.of(new PriceBook("litellm-a"));

        @Nullable
        BigDecimal cacheWritePerMtok = new BigDecimal("3.75");

        int rateReads;

        Books() {
            super(null, null);
        }

        @Override
        public List<PriceBook> currentBooks() {
            return inForce;
        }

        @Override
        public boolean hasModel(String modelId) {
            return "claude-x".equals(modelId);
        }

        @Override
        public Optional<ModelRate> rateFor(String modelId) {
            rateReads++;
            return Optional.of(new ModelRate(
                    inForce.get(0).version(),
                    new ModelRates(
                            new BigDecimal("3"), new BigDecimal("15"), new BigDecimal("0.3"), cacheWritePerMtok)));
        }
    }

    /**
     * A sweep asks once per span, so an answer is kept across the periodic recheck while the same books are
     * in force; a new book set discards every answer, so a changed rate is picked up without a restart.
     */
    @Test
    void cacheCreationAnswersLastAsLongAsTheBooksInForce() {
        StepClock clock = new StepClock(Instant.parse("2026-09-01T00:00:00Z"));
        Books books = new Books();
        ModelResolver resolver = new ModelResolver(books, clock);

        assertTrue(resolver.reportedModelBillsCacheCreation(" Claude-X "));
        clock.advance(Duration.ofMinutes(6));
        assertTrue(resolver.reportedModelBillsCacheCreation("claude-x"));
        assertEquals(1, books.rateReads, "the same books past the recheck keep the answer they gave");

        books.inForce = List.of(new PriceBook("litellm-b"));
        books.cacheWritePerMtok = null;
        clock.advance(Duration.ofMinutes(6));
        assertFalse(resolver.reportedModelBillsCacheCreation("claude-x"), "the new book bills no cache writes");
        assertEquals(2, books.rateReads);
    }
}
