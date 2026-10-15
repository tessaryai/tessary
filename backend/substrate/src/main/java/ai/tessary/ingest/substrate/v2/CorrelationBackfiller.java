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
 * Copies correlation handles from a trace onto spans that arrived without them (third-party producers without our
 * Baggage), as a micro-batch over {@code ix_span_uncorrelated}. Spans of anonymous traces are marked
 * {@code correlation_state = 'none'} at settle so they cannot starve the queue; a later batch that brings the session
 * puts them back ({@link SpanRepository#adoptTraceSession}). The kill switch lets an operator stop a once-a-second job.
 */
@Component
@ConditionalOnProperty(
        prefix = "tessary.ingest.substrate",
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

    public record Pass(int correlated, int anonymous) {

        int total() {
            return correlated + anonymous;
        }
    }

    @Scheduled(
            fixedDelayString = "${tessary.ingest.substrate.resolver-interval-ms:1000}",
            initialDelayString = "${tessary.ingest.substrate.resolver-interval-ms:1000}")
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

    /** The seam the starvation test drives. */
    public Pass runOnce() {
        Instant started = Instant.now();
        int limit = props.resolverBatchSize();
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
