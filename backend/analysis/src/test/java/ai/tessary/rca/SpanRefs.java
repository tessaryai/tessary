// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.FindingEvidenceRepository.SpanRef;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Evidence rows by the fields a test names, since the record's positional constructor is long. */
final class SpanRefs {

    private SpanRefs() {}

    static Builder row(String role) {
        return new Builder(role);
    }

    /** The finding_evidence rows the given span rows were read from. */
    static List<FindingEvidenceRow> recorded(List<SpanRef> rows) {
        List<FindingEvidenceRow> out = new ArrayList<>();
        for (SpanRef row : rows) {
            out.add(new FindingEvidenceRow(
                    "ev-" + out.size(),
                    "proj-1",
                    "f1",
                    row.sessionId(),
                    row.traceId(),
                    row.spanId(),
                    row.role(),
                    null,
                    "2026-10-01T00:00:00Z"));
        }
        return out;
    }

    static final class Builder {
        private final String role;
        private @Nullable String sessionId;
        private @Nullable String traceId;
        private @Nullable String spanId;
        private @Nullable String name;
        private @Nullable String kind;
        private @Nullable String status;
        private @Nullable String level;
        private @Nullable String errorType;
        private @Nullable String startedAt;
        private @Nullable Long latencyMs;
        private @Nullable Long totalTokens;
        private @Nullable Double totalCost;
        private List<String> models = List.of();
        private @Nullable String callSiteId;
        private @Nullable String projectVersionId;
        private @Nullable String userId;
        private @Nullable Long inputTokens;
        private @Nullable Long outputTokens;
        private @Nullable Long cacheReadTokens;
        private @Nullable Long cacheWriteTokens;
        private @Nullable Long reasoningTokens;
        private @Nullable Integer traceIndex;
        private @Nullable Integer sessionTraces;
        private @Nullable Long inputBytes;
        private @Nullable Long outputBytes;
        private boolean payloadAvailable;
        private Map<String, String> attributes = Map.of();

        private Builder(String role) {
            this.role = role;
        }

        Builder session(String id) {
            this.sessionId = id;
            return this;
        }

        Builder trace(String id) {
            this.traceId = id;
            return this;
        }

        Builder span(String traceId, String spanId) {
            this.traceId = traceId;
            this.spanId = spanId;
            return this;
        }

        Builder name(String value) {
            this.name = value;
            return this;
        }

        Builder kind(String value) {
            this.kind = value;
            return this;
        }

        Builder status(String value) {
            this.status = value;
            return this;
        }

        Builder level(String value) {
            this.level = value;
            return this;
        }

        Builder errorType(String value) {
            this.errorType = value;
            return this;
        }

        Builder startedAt(String value) {
            this.startedAt = value;
            return this;
        }

        Builder latencyMs(long value) {
            this.latencyMs = value;
            return this;
        }

        Builder totalTokens(long value) {
            this.totalTokens = value;
            return this;
        }

        Builder totalCost(double value) {
            this.totalCost = value;
            return this;
        }

        Builder models(String... values) {
            this.models = List.of(values);
            return this;
        }

        Builder callSiteId(String value) {
            this.callSiteId = value;
            return this;
        }

        Builder projectVersionId(String value) {
            this.projectVersionId = value;
            return this;
        }

        Builder userId(String value) {
            this.userId = value;
            return this;
        }

        Builder inputTokens(long value) {
            this.inputTokens = value;
            return this;
        }

        Builder outputTokens(long value) {
            this.outputTokens = value;
            return this;
        }

        Builder cacheReadTokens(long value) {
            this.cacheReadTokens = value;
            return this;
        }

        Builder cacheWriteTokens(long value) {
            this.cacheWriteTokens = value;
            return this;
        }

        Builder reasoningTokens(long value) {
            this.reasoningTokens = value;
            return this;
        }

        Builder traceIndex(int value) {
            this.traceIndex = value;
            return this;
        }

        Builder sessionTraces(int value) {
            this.sessionTraces = value;
            return this;
        }

        Builder inputBytes(long value) {
            this.inputBytes = value;
            return this;
        }

        Builder outputBytes(long value) {
            this.outputBytes = value;
            return this;
        }

        Builder payloadAvailable(boolean value) {
            this.payloadAvailable = value;
            return this;
        }

        Builder attributes(Map<String, String> value) {
            this.attributes = value;
            return this;
        }

        SpanRef build() {
            return new SpanRef(
                    role,
                    null,
                    sessionId,
                    traceId,
                    spanId,
                    name,
                    kind,
                    status,
                    level,
                    errorType,
                    startedAt,
                    latencyMs,
                    totalTokens,
                    totalCost,
                    models,
                    false,
                    false,
                    false,
                    callSiteId,
                    projectVersionId,
                    userId,
                    inputTokens,
                    outputTokens,
                    cacheReadTokens,
                    cacheWriteTokens,
                    reasoningTokens,
                    traceIndex,
                    sessionTraces,
                    inputBytes,
                    outputBytes,
                    payloadAvailable,
                    attributes,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
