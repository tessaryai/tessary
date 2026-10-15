// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Repository.Claim;
import ai.tessary.storage.TraceV2Repository.Recomputed;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The claim clears {@code rollup_due_at}, and a span arriving after it re-arms the deadline in its own transaction, so
 * the write settles only if the deadline is still clear. Rollups are replacements, not deltas, so there is no lost
 * update; {@code is_settled} never means "stopped waiting". No lease or job row: a crashed claim leaves a fingerprint
 * {@link TraceRollupReaper} sweeps back. Per-trace failures are caught per trace, safe here because each recompute
 * autocommits. {@code rollup-enabled} is an ops kill switch; deadlines stay armed while it is off.
 */
@Component
@ConditionalOnProperty(
        prefix = "tessary.ingest.substrate",
        name = "rollup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TraceRollupWorker {

    private static final Logger log = LoggerFactory.getLogger(TraceRollupWorker.class);

    /** Drains a burst without spinning on a queue refilled as fast as it drains. */
    private static final int MAX_CLAIM_ROUNDS = 100;

    private final TraceV2Repository traces;
    private final TraceRollupMetrics metrics;
    private final SubstrateProperties props;

    public TraceRollupWorker(TraceV2Repository traces, TraceRollupMetrics metrics, SubstrateProperties props) {
        this.traces = traces;
        this.metrics = metrics;
        this.props = props;
    }

    /**
     * @param settled the shortfall from recomputed is spans landing mid-rollup @param vanished deleted under the
     * worker; normal at a retention boundary @param failed each leaves the reaper's fingerprint
     */
    public record Pass(int claimed, int recomputed, int settled, int vanished, int failed) {}

    @Scheduled(
            fixedDelayString = "${tessary.ingest.substrate.rollup-interval-ms:1000}",
            initialDelayString = "${tessary.ingest.substrate.rollup-interval-ms:1000}")
    public void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // Categorical only: a Postgres error can echo span content, and OPS WARN egresses.
            log.warn(
                    Markers.OPS,
                    "trace rollup pass failed error={}",
                    e.getClass().getSimpleName());
            log.debug("trace rollup failure detail", e);
        }
    }

    /**
     * Synchronous on the scheduler thread so {@code fixedDelay} guarantees passes never overlap. Public so tests can
     * assert deterministically.
     */
    public Pass runOnce() {
        Instant started = Instant.now();
        int limit = props.getRollupClaimLimit();
        int claimed = 0;
        int recomputed = 0;
        int settled = 0;
        int vanished = 0;
        int failed = 0;

        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            List<Claim> batch = traces.claimDue(limit);
            if (batch.isEmpty()) break;
            claimed += batch.size();
            for (Claim claim : batch) {
                try {
                    Optional<Recomputed> result = traces.recompute(claim.projectId(), claim.traceId());
                    if (result.isEmpty()) {
                        vanished++;
                    } else {
                        recomputed++;
                        if (result.get().settled()) settled++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn(
                            Markers.OPS,
                            "trace rollup recompute failed project={} error={}",
                            claim.projectId(),
                            e.getClass().getSimpleName());
                    log.debug("trace rollup recompute failure detail", e);
                }
            }
            // A short round means the queue is drained.
            if (batch.size() < limit) break;
        }

        Pass pass = new Pass(claimed, recomputed, settled, vanished, failed);
        metrics.recordPass(claimed, recomputed, settled, failed);
        if (claimed > 0) {
            StructuredLog.info(log, Markers.OPS, "substrate.rollup.pass")
                    .message(
                            "rolled up %s trace(s), %s settled, %s still receiving spans",
                            recomputed, settled, recomputed - settled)
                    .field("claimed", claimed)
                    .field("recomputed", recomputed)
                    .field("settled", settled)
                    .field("vanished", vanished)
                    .field("failed", failed)
                    .durationMs(started)
                    .log();
        }
        return pass;
    }
}
