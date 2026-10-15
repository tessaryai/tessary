// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** A call site's production stats, computed from ingested traces at synthesis time. */
public record Observed(
        @Nullable @JsonProperty("first_seen") String firstSeen,
        @Nullable @JsonProperty("last_seen") String lastSeen,
        @Nullable @JsonProperty("error_rate") Double errorRate,
        @Nullable @JsonProperty("refusal_rate") Double refusalRate,
        @Nullable @JsonProperty("p50_latency_ms") Integer p50LatencyMs,
        @Nullable @JsonProperty("p95_latency_ms") Integer p95LatencyMs,
        @Nullable @JsonProperty("p50_tokens_in") Integer p50TokensIn,
        @Nullable @JsonProperty("p95_tokens_in") Integer p95TokensIn,
        @Nullable @JsonProperty("p95_tokens_out") Integer p95TokensOut,
        @Nullable @JsonProperty("cost_estimate_usd") Double costEstimateUsd,

        @Nullable @Schema(allowableValues = {"none", "partial", "redacted", "unknown"}) @JsonProperty("redaction_state")
        String redactionState) {}
