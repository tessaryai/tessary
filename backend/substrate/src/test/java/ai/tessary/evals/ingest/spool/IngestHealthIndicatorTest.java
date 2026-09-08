// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.spool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.evals.config.IngestSpoolProperties;
import ai.tessary.evals.ingest.substrate.SubstrateWriter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** The {@code ingest} health component (#984, criterion 4): the spool's numbers, DOWN on a dead drainer or a stale backlog. */
class IngestHealthIndicatorTest {

    private final SubstrateWriter writer = mock(SubstrateWriter.class);
    private final IngestSpoolProperties props = new IngestSpoolProperties();
    private final IngestSpool spool = mock(IngestSpool.class);

    private Health health(boolean alive, long oldestAgeMs, long deadLettered) {
        when(writer.drainerAlive()).thenReturn(alive);
        when(writer.spoolStats())
                .thenReturn(new IngestSpool.Stats("memory", false, 3, 4096, 67_108_864, oldestAgeMs, deadLettered));
        return new IngestHealthIndicator(writer, spool, props).health();
    }

    @Test
    void reportsTheSpoolAndIsUpWhileDrainingKeepsUp() {
        Health h = health(true, 250, 0);
        assertEquals(Status.UP, h.getStatus());
        assertEquals("memory", h.getDetails().get("spool"));
        assertEquals(3L, h.getDetails().get("depth"));
        assertEquals(250L, h.getDetails().get("oldestAgeMs"));
        assertEquals(false, h.getDetails().get("durable"));
    }

    @Test
    void downWhenTheSpoolSaysItsBrokerIsUnreachable() {
        when(spool.unavailable()).thenReturn(Optional.of("broker not reachable at redpanda:9092"));
        Health h = health(true, 0, 0);
        assertEquals(Status.DOWN, h.getStatus());
        assertEquals("broker not reachable at redpanda:9092", h.getDetails().get("unavailable"));
    }

    @Test
    void downWhenTheDrainerIsDead() {
        assertEquals(Status.DOWN, health(false, 0, 0).getStatus());
    }

    @Test
    void downWhenTheOldestBatchIsOlderThanTheLagBudget() {
        props.setMaxLagMs(1_000);
        assertEquals(Status.DOWN, health(true, 5_000, 2).getStatus());
        props.setMaxLagMs(0);
        assertEquals(Status.UP, health(true, 5_000, 2).getStatus(), "0 disables the lag check");
    }
}
