// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ai.tessary.config.PricingProperties;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

/** The boot-time import of the vendored rate file, against a price-book store that answers on cue. */
@ExtendWith(MockitoExtension.class)
class PriceBookImporterTest {

    @Mock
    PriceBookRepository books;

    private final Logger logger = (Logger) LoggerFactory.getLogger(PriceBookImporter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private PriceBookImporter importer() {
        return new PriceBookImporter(
                books,
                new PricingProperties(),
                new ObjectMapper(),
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class));
    }

    /** Rates the platform can run without must never be able to stop it starting. */
    @Test
    void aFailingImportOnBootNeverStopsTheApplication() {
        when(books.hasBook(anyString())).thenThrow(new IllegalStateException("relation price_book does not exist"));

        assertDoesNotThrow(importer()::importOnBoot);
    }

    /**
     * Only the instance whose write landed announces the import; one that lost the race to another
     * instance says nothing, so the operations log counts each book once.
     */
    @ParameterizedTest
    @CsvSource({"true, 1", "false, 0"})
    void onlyTheInstanceThatImportedTheBookAnnouncesIt(boolean applied, long announcements) {
        when(books.hasBook(anyString())).thenReturn(false);
        when(books.importBook(any(), any())).thenReturn(new PriceBookRepository.Imported(applied, 3, 10));

        importer().importSnapshots();

        assertEquals(
                announcements,
                appender.list.stream()
                        .filter(e -> e.getKeyValuePairs() != null
                                && e.getKeyValuePairs().stream()
                                        .anyMatch(kv ->
                                                "event".equals(kv.key) && "pricing.book.imported".equals(kv.value)))
                        .count());
    }
}
