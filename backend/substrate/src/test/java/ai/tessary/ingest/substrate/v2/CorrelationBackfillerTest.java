// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.storage.SpanRepository;
import ai.tessary.testsupport.LogCapture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CorrelationBackfillerTest {

    @Mock
    SpanRepository spans;

    @RegisterExtension
    final LogCapture log = LogCapture.of(CorrelationBackfiller.class);

    /**
     * A failed pass is survived and reported by its class alone: a database error can echo span content,
     * and the operations log it goes to leaves the box.
     */
    @Test
    void aFailedPassIsReportedWithoutTheDatabaseError() {
        when(spans.backfillCorrelation(anyInt()))
                .thenThrow(new IllegalStateException("value \"secret prompt\" too long"));

        assertDoesNotThrow(new CorrelationBackfiller(spans, new SubstrateProperties())::tick);

        ILoggingEvent warn = log.first(Level.WARN);
        assertNull(warn.getThrowableProxy(), "no stack trace, and no message, on the egressing line");
        assertEquals(List.of("IllegalStateException"), List.of(warn.getArgumentArray()));
    }
}
