// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * What a detector ASSERTED, with nothing that was later ruled about it.
 *
 * <p>This projection exists for exactly one reader — RCA — and it is the context firewall in record
 * form. Layer 2 runs a cheap model over the same finding and writes {@code triage_verdict},
 * {@code triage_summary}, {@code triage_citations} and {@code triage_action} onto the row; those are
 * Layer 2's conclusions, and a mistake in them must never arrive at Layer 3 as a premise. A person may
 * read every one of them and then press "Run RCA"; the agent may not.
 *
 * <p>Enforcement is the SELECT list, not a convention: {@code FindingRepository.findClaim} names these
 * columns and no others, so there is no triage value in scope for a dossier builder to leak by
 * accident. Anything that needs the rulings reads {@link FindingRow} instead.
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

    /** How many refs the detector wrote under one {@link FindingEvidenceRow.Role} — see
     *  {@link FindingRow#evidenceCount}, which reads the same counter the same way. */
    public long evidenceCount(String role) {
        return FindingPayload.count(evidenceCountsJson, role);
    }

    /** The classifier's own cause key, with the uniqueness scope stripped back off. */
    public String nativeCauseKey() {
        String native0 = FindingPayload.text(payloadJson, "native_cause_key");
        return native0 == null ? causeKey : native0;
    }

    /** The classifier family's own name for the shape it saw, falling back to {@link #classifierKey}
     *  for a family that has no such taxonomy — see {@link FindingRow#causeKind}. */
    public String causeKind() {
        String kind = FindingPayload.text(payloadJson, "cause_kind");
        return kind == null ? classifierKey : kind;
    }
}
