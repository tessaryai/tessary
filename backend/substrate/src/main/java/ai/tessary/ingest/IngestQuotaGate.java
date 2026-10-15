// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

/**
 * The admission gate for {@code ingested_spans_monthly}. An interface because the capability layer lives in
 * {@code product}, above {@code substrate}. Enforcement lags by up to an hour plus a heartbeat, since it reads closed
 * {@code metric_rollup} buckets; live-counting spans per export would put an unbounded aggregate on the hot path.
 */
public interface IngestQuotaGate {

    /** 402 {@code QUOTA_EXCEEDED}. Uncapped orgs and unknown projects never throw. */
    void requireIngestWithinQuota(String projectId);
}
