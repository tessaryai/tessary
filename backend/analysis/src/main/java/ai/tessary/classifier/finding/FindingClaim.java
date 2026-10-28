// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * What a detector asserted, without anything later ruled about it: the context firewall for RCA. Triage's conclusions
 * must never reach Layer 3 as premises. Enforced by the SELECT list in {@code FindingRepository.findClaim}, so no
 * triage value is in scope to leak; anything needing rulings reads {@link FindingRow}.
 */
public record FindingClaim(
        String id,
        String projectId,
        String classifierKey,
        String causeKey,
        String subjectKind,
        String subjectId,
        @Nullable String subjectLabel,
        @Nullable String callSiteId,
        String onsetAt,
        String lastSeenAt,
        @Nullable String title,
        @Nullable String basis,
        @Nullable Double severity,
        long sampleCount,
        @Nullable String payloadJson,
        @Nullable String evidenceCountsJson,
        String createdAt) {

    /** Reads the same counter as {@link FindingRow#evidenceCount}. */
    public long evidenceCount(String role) {
        return FindingPayload.count(evidenceCountsJson, role);
    }

    /** Null when absent or not text. */
    public @Nullable String payloadText(String key) {
        return FindingPayload.text(payloadJson, key);
    }

    /** As {@link FindingRow#causeKind}: the payload's {@code cause_kind}, else {@link #classifierKey}. */
    public String causeKind() {
        String kind = payloadText("cause_kind");
        return kind == null ? classifierKey : kind;
    }

    /** 0 when absent or not a number. */
    public double payloadNumber(String key) {
        JsonNode node = FindingPayload.tree(payloadJson).path(key);
        return node.isNumber() ? node.asDouble() : 0.0;
    }

    /** With the uniqueness scope stripped back off. */
    public String nativeCauseKey() {
        String native0 = FindingPayload.text(payloadJson, "native_cause_key");
        return native0 == null ? causeKey : native0;
    }
}
