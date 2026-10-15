// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.SpanRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Materializes {@code span.path} as a micro-batch fixpoint over {@code ix_span_unresolved_path}: parents ship after
 * children, so a depth-n chain converges in n ticks. Null path means unresolved, never root. Children of never-shipped
 * parents are marked {@code orphan} at settle so they cannot starve the batch; a late parent puts them back to
 * {@code pending}. The kill switch lets an operator stop a once-a-second job competing with ingest.
 */
@Component
@ConditionalOnProperty(
        prefix = "tessary.ingest.substrate",
        name = "resolvers-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PathResolver {

    private static final Logger log = LoggerFactory.getLogger(PathResolver.class);

    private final SpanRepository spans;
    private final SubstrateProperties props;

    public PathResolver(SpanRepository spans, SubstrateProperties props) {
        this.spans = spans;
        this.props = props;
    }

    public record Pass(int roots, int children, int orphans) {

        int total() {
            return roots + children + orphans;
        }
    }

    @Scheduled(
            fixedDelayString = "${tessary.ingest.substrate.resolver-interval-ms:1000}",
            initialDelayString = "${tessary.ingest.substrate.resolver-interval-ms:1000}")
    public void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // Categorical only: a Postgres error can echo span content, and OPS WARN egresses. The next tick retries.
            log.warn(
                    Markers.OPS,
                    "span path resolution pass failed error={}",
                    e.getClass().getSimpleName());
            log.debug("span path resolution failure detail", e);
        }
    }

    /** Public so a test can count passes rather than race the scheduler. */
    public Pass runOnce() {
        Instant started = Instant.now();
        int limit = props.getResolverBatchSize();
        Pass pass =
                new Pass(spans.resolveRootPaths(limit), spans.resolveChildPaths(limit), spans.markOrphanPaths(limit));
        if (pass.total() > 0) {
            StructuredLog.info(log, Markers.OPS, "substrate.path.resolved")
                    .message(
                            "resolved %s span path(s), retired %s orphan(s)",
                            pass.roots() + pass.children(), pass.orphans())
                    .field("roots", pass.roots())
                    .field("children", pass.children())
                    .field("orphans", pass.orphans())
                    .field("pending", spans.pendingPathCount())
                    .durationMs(started)
                    .log();
        }
        return pass;
    }
}
