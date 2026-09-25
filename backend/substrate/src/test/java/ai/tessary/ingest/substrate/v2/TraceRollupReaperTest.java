// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.storage.TraceV2Repository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * The reaper's two alarms. Both are ERROR on purpose: a queue that has fallen behind and a sweep that has
 * stopped running both look, from every trace surface, exactly like nothing being wrong.
 */
@ExtendWith(MockitoExtension.class)
class TraceRollupReaperTest {

    @Mock
    TraceV2Repository traces;

    private final Logger logger = (Logger) LoggerFactory.getLogger(TraceRollupReaper.class);
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

    private TraceRollupReaper reaper() {
        SubstrateProperties props = new SubstrateProperties();
        props.setRollupStaleAfterMs(60_000);
        return new TraceRollupReaper(traces, new TraceRollupMetrics(), props);
    }

    /** Behind by more than the stale threshold is an alarm; at the threshold it is not yet. */
    @ParameterizedTest
    @CsvSource({"60001, 1", "60000, 0", "0, 0"})
    void aQueueBehindItsStaleThresholdRaisesAnError(long overdueMs, long errors) {
        when(traces.reap(anyInt())).thenReturn(0);
        when(traces.queueStats()).thenReturn(new TraceV2Repository.RollupQueue(4, overdueMs));

        assertEquals(new TraceRollupReaper.Sweep(0, 4, overdueMs), reaper().sweepOnce());
        assertEquals(errors, errors());
    }

    /** A failed sweep is survived, raised at ERROR, and carries only the error's class. */
    @Test
    void aFailedSweepIsRaisedWithoutTheDatabaseError() {
        when(traces.reap(anyInt())).thenThrow(new IllegalStateException("value \"secret prompt\" too long"));

        assertDoesNotThrow(reaper()::tick);

        ILoggingEvent error = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow();
        assertNull(error.getThrowableProxy());
        assertEquals(List.of("IllegalStateException"), List.of(error.getArgumentArray()));
    }

    private long errors() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }
}
