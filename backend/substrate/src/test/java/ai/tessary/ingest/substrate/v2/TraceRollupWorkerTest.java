// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.testsupport.LogCapture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A rollup pass over claims the database answers in every way it can: written, vanished, or failed. */
@ExtendWith(MockitoExtension.class)
@Isolated
class TraceRollupWorkerTest {

    @Mock
    TraceV2Repository traces;

    @RegisterExtension
    final LogCapture log = LogCapture.of(TraceRollupWorker.class);

    private TraceRollupWorker worker() {
        return new TraceRollupWorker(traces, new TraceRollupMetrics(), new SubstrateProperties());
    }

    /** One trace deleted under the worker and one that fails to recompute cost only themselves. */
    @Test
    void aPassCountsVanishedAndFailedTracesAndStillRecomputesTheRest() {
        when(traces.claimDue(anyInt()))
                .thenReturn(List.of(
                        new TraceV2Repository.Claim("p", "gone"),
                        new TraceV2Repository.Claim("p", "broken"),
                        new TraceV2Repository.Claim("p", "fine")));
        when(traces.recompute("p", "gone")).thenReturn(Optional.empty());
        when(traces.recompute("p", "broken")).thenThrow(new IllegalStateException("deadlock detected"));
        when(traces.recompute("p", "fine")).thenReturn(Optional.of(new TraceV2Repository.Recomputed(true, 2)));

        assertEquals(new TraceRollupWorker.Pass(3, 1, 1, 1, 1), worker().runOnce());
    }

    /**
     * A failed pass is survived and reported by its class alone: a database error can echo span content,
     * and the operations log it goes to leaves the box.
     */
    @Test
    void aFailedPassIsReportedWithoutTheDatabaseError() {
        when(traces.claimDue(anyInt())).thenThrow(new IllegalStateException("value \"secret prompt\" too long"));

        assertDoesNotThrow(worker()::tick);

        ILoggingEvent warn = log.first(Level.WARN);
        assertNull(warn.getThrowableProxy(), "no stack trace, and no message, on the egressing line");
        assertEquals(List.of("IllegalStateException"), List.of(warn.getArgumentArray()));
    }
}
