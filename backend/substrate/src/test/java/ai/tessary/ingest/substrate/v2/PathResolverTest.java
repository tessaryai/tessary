// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.storage.SpanRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class PathResolverTest {

    @Mock
    SpanRepository spans;

    private final Logger logger = (Logger) LoggerFactory.getLogger(PathResolver.class);
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

    /**
     * A failed pass is survived and reported by its class alone: a database error can echo span content,
     * and the operations log it goes to leaves the box.
     */
    @Test
    void aFailedPassIsReportedWithoutTheDatabaseError() {
        when(spans.resolveRootPaths(anyInt())).thenThrow(new IllegalStateException("value \"secret prompt\" too long"));

        assertDoesNotThrow(new PathResolver(spans, new SubstrateProperties())::tick);

        ILoggingEvent warn = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .findFirst()
                .orElseThrow();
        assertNull(warn.getThrowableProxy(), "no stack trace, and no message, on the egressing line");
        assertEquals(List.of("IllegalStateException"), List.of(warn.getArgumentArray()));
    }

    /** The scheduled tick is the pass: each one asks every resolver statement for one batch. */
    @Test
    void aTickRunsOnePass() {
        when(spans.resolveRootPaths(500)).thenReturn(0);
        when(spans.resolveChildPaths(500)).thenReturn(0);
        when(spans.markOrphanPaths(500)).thenReturn(0);

        new PathResolver(spans, new SubstrateProperties()).tick();

        verify(spans).resolveRootPaths(500);
        verify(spans).resolveChildPaths(500);
        verify(spans).markOrphanPaths(500);
    }
}
