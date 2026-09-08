// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate.v2;

import ai.tessary.evals.config.SubstrateProperties;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import ai.tessary.evals.storage.SpanRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Copies correlation handles down from a trace onto spans that arrived without them, as a micro-batch over
 * {@code ix_span_uncorrelated} (substrate-model.md §6.3).
 *
 * <h2>The fallback, not the path</h2>
 *
 * <p>Every span is supposed to describe itself: our SDK puts the session, user and trace name in
 * OpenTelemetry Baggage, so the correlation columns are populated at write and no server-side join is
 * needed to read a span. This exists for third-party OTel producers that propagate none of that, whose
 * spans land with a null {@code session_id} and only find out which session they belong to once their
 * trace row states it.
 *
 * <h2>Anonymous traffic is a terminal state, not a backlog</h2>
 *
 * <p>A trace with no session id is not a trace whose session is late — it is anonymous traffic, and there
 * will never be anything to copy. Left {@code pending}, every one of those spans would be re-read on every
 * tick forever; anonymous traffic is a large and permanent population, so as soon as it outgrew the batch
 * limit the spans that DID have a session waiting for them would stop being selected at all. Marking them
 * {@code correlation_state = 'none'} once their trace settles is what keeps the queue drainable — the same
 * device, for the same reason, as {@code path_state = 'orphan'}.
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
        prefix = "evals.ingest.substrate",
        name = "resolvers-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CorrelationBackfiller {

    private static final Logger log = LoggerFactory.getLogger(CorrelationBackfiller.class);

    private final SpanRepository spans;
    private final SubstrateProperties props;

    public CorrelationBackfiller(SpanRepository spans, SubstrateProperties props) {
        this.spans = spans;
        this.props = props;
    }

    /** One tick's work: spans given their trace's handles, and spans retired as never having any. */
    public record Pass(int correlated, int anonymous) {

        int total() {
            return correlated + anonymous;
        }
    }

    @Scheduled(
            fixedDelayString = "${evals.ingest.substrate.resolver-interval-ms:1000}",
            initialDelayString = "${evals.ingest.substrate.resolver-interval-ms:1000}")
    public void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn(
                    Markers.OPS,
                    "span correlation pass failed error={}",
                    e.getClass().getSimpleName());
            log.debug("span correlation failure detail", e);
        }
    }

    /** Run one pass synchronously and report what it did — the seam the starvation test drives. */
    public Pass runOnce() {
        Instant started = Instant.now();
        int limit = props.getResolverBatchSize();
        Pass pass = new Pass(spans.backfillCorrelation(limit), spans.markCorrelationNone(limit));
        if (pass.total() > 0) {
            StructuredLog.info(log, Markers.OPS, "substrate.correlation.backfilled")
                    .message("correlated %s span(s), retired %s anonymous", pass.correlated(), pass.anonymous())
                    .field("correlated", pass.correlated())
                    .field("anonymous", pass.anonymous())
                    .field("pending", spans.pendingCorrelationCount())
                    .durationMs(started)
                    .log();
        }
        return pass;
    }
}
