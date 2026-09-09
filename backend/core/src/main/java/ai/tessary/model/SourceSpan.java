// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Pointer to one OTel span captured from the ingested traces at synthesis
 * time. Lets the viewer deep-link from a call site / grader back to the
 * evidence that produced it.
 */
public record SourceSpan(
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("span_id") String spanId,
        @JsonProperty("parent_span_id") @Nullable String parentSpanId,
        @JsonProperty("service_name") @Nullable String serviceName,
        @Nullable String timestamp) {}
