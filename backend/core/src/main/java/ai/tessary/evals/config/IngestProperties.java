// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Ingestion tuning, bound from {@code evals.ingest.*}. Today this carries the
 * out-of-band media resolver's bounds (see {@code ingest/MediaResolver}): the
 * per-file download cap and the per-page fetch budget. The resolver itself always
 * runs (there is no on/off toggle) — but a media-free payload is a cheap no-op, so
 * a stock import that carries no media still issues no extra media-download call.
 *
 * <p>Distinct from {@link EvalsProperties} (DB connection + the bundled-yaml seed
 * anchor) by concern, mirroring the per-prefix split used by {@code ObserverProperties}
 * / {@code SynthProperties}.
 */
@Component
@ConfigurationProperties(prefix = "evals.ingest")
public class IngestProperties {

    /**
     * Per-file ceiling for a single downloaded media object, in bytes. Distinct from
     * {@code BoundedBody.MAX_RESPONSE_BYTES} (32 MB, sized for a JSON trace page): a single
     * inline image rarely needs more than a few MB, and inlining it base64 into a TEXT column
     * roughly inflates it 1.33x, so keep the cap tight. The resolver downloads each media URL
     * through {@code BoundedBody.string(maxMediaBytes)} so a hostile/oversized object is aborted
     * mid-stream rather than buffered. Default 8 MB (covers a 5 MP screenshot with headroom).
     */
    private long maxMediaBytes = 8L * 1024 * 1024;

    /**
     * Max media objects the resolver will fetch per observation page. The resolver batches at
     * page granularity (after a page parses) and bounds the count so a page dense with media
     * can't fan out into a serial per-observation HTTP multiplier that stalls the import; media
     * beyond the cap on a page is logged and skipped (the trace still imports, just without those
     * images). Default 10.
     */
    private int maxMediaFetchesPerPage = 10;

    public long getMaxMediaBytes() {
        return maxMediaBytes;
    }

    public void setMaxMediaBytes(long v) {
        this.maxMediaBytes = v;
    }

    public int getMaxMediaFetchesPerPage() {
        return maxMediaFetchesPerPage;
    }

    public void setMaxMediaFetchesPerPage(int v) {
        this.maxMediaFetchesPerPage = v;
    }
}
