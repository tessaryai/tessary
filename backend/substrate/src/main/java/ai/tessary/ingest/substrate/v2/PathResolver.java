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
 * Materializes span ancestry — {@code span.path} — as a micro-batch fixpoint over
 * {@code ix_span_unresolved_path} (substrate-model.md §6.4).
 *
 * <h2>Why this is a job and not part of the write</h2>
 *
 * <p>A batch exporter flushes a span when it ENDS, so a parent always ships after its children and a root
 * ships last. {@code parent_span_id} is never in doubt — it is the producer's statement, stored verbatim
 * on arrival — but the DERIVED ancestry cannot be computed until the parent's own path exists. Each tick
 * resolves one level; because chains arrive deepest-first, a chain of depth <i>n</i> converges in at most
 * <i>n</i> ticks, and each pass is a small indexed scan rather than a scan over everything already done.
 *
 * <p><b>A null path means "not resolved yet", never "root".</b> Root is {@code parent_span_id IS NULL},
 * and roots are given their one-label path by the same tick. Subtree reads (§9) are complete once the
 * trace has settled.
 *
 * <h2>The orphan terminal state</h2>
 *
 * <p>Some parents are never shipped at all. Without a terminal state their children sit in the partial
 * index forever, and once those permanent residents outnumber the batch limit the fixpoint stops selecting
 * anything that could still make progress — a starvation bug that looks like a resolver quietly doing
 * nothing. Marking them {@code path_state = 'orphan'} once their trace has settled takes them out of the
 * index and keeps the spec's "near-empty by construction" claim true.
 *
 * <h2>The kill switch</h2>
 *
 * <p>These two are the only {@code @Scheduled} beans in the backend that tick every second, so they carry
 * one — default ON, the same shape a background job worker would. It is not a rollout flag: it exists so
 * an operator can stop a background job competing with ingest for connections, and so a test that counts
 * fixpoint passes is not racing a scheduler while it does.
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

    /** One tick's work: roots given their own label, one level of the fixpoint, and orphan retirement. */
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
            // Categorical only: a Postgres error can echo span content into its message, and OPS WARN
            // egresses. Detail stays local. The rows are durable and unchanged; the next tick retries.
            log.warn(
                    Markers.OPS,
                    "span path resolution pass failed error={}",
                    e.getClass().getSimpleName());
            log.debug("span path resolution failure detail", e);
        }
    }

    /**
     * Run one pass synchronously and report what it did. Public so the fixpoint can be driven
     * deterministically — a test that asserts "a depth-n chain converges in n passes" has to be able to
     * count the passes rather than race a scheduler.
     */
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
