// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.spool;

import ai.tessary.evals.config.IngestSpoolProperties;
import ai.tessary.evals.ingest.substrate.SubstrateWriter;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The {@code ingestSpool} health contributor, served through the {@code /actuator/health/ingest} group (#984, criterion 4): the spool's depth, bytes,
 * oldest unprocessed age and dead-lettered count, and how many of the drainers are alive, so a self-hoster
 * can ask one HTTP endpoint whether accepted data is reaching the substrate instead of scraping the
 * once-a-minute throughput log line. DOWN when any drainer is dead (health used to say UP while the
 * queue silently stopped draining) when the oldest queued batch is older than
 * {@code evals.ingest.spool.max-lag-ms}, or when the spool reports itself unavailable (a durable
 * spool whose broker is not answering).
 */
@Component("ingestSpool")
public class IngestHealthIndicator implements HealthIndicator {

    private final SubstrateWriter writer;
    private final IngestSpool spool;
    private final IngestSpoolProperties props;

    public IngestHealthIndicator(SubstrateWriter writer, IngestSpool spool, IngestSpoolProperties props) {
        this.writer = writer;
        this.spool = spool;
        this.props = props;
    }

    @Override
    public Health health() {
        IngestSpool.Stats stats = writer.spoolStats();
        boolean alive = writer.drainerAlive();
        boolean lagging = props.getMaxLagMs() > 0 && stats.oldestAgeMs() > props.getMaxLagMs();
        Optional<String> unavailable = spool.unavailable();
        Health.Builder h = alive && !lagging && unavailable.isEmpty() ? Health.up() : Health.down();
        unavailable.ifPresent(reason -> h.withDetail("unavailable", reason));
        return h.withDetail("spool", stats.mode())
                .withDetail("durable", stats.durable())
                .withDetail("depth", stats.depth())
                .withDetail("bytes", stats.bytes())
                .withDetail("maxBytes", stats.maxBytes())
                .withDetail("oldestAgeMs", stats.oldestAgeMs())
                .withDetail("deadLettered", stats.deadLettered())
                .withDetail("drainerAlive", alive)
                .withDetail("drainersAlive", writer.drainersAlive())
                .withDetail("drainers", writer.drainerCount())
                .withDetail("shedBatches", writer.shedBatches())
                .withDetail("failedBatches", writer.failedBatches())
                .build();
    }
}
