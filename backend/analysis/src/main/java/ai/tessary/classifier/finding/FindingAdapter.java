// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.dossier.EvidenceContext;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Gives findings from classifiers outside the built-in catalog what a built-in's get: triage escalation, dossier method
 * and evidence files, a {@code classifierDetail} on finding and case views, and a detector-state write on absorb.
 * Reached through {@link FindingAdapters} only; the open edition ships none.
 */
public interface FindingAdapter {

    /** Answers the same for every finding of one project and classifier key: escalation asks once per key. */
    boolean owns(FindingClaim finding);

    /** Takes the claim only, so no ruling reaches a dossier through it. */
    Optional<String> triageMethod(FindingClaim finding);

    /** Takes the claim only, so no ruling reaches the RCA dossier through it. */
    Optional<String> rcaMethod(FindingClaim finding);

    /** Empty keeps the shape assembler. The caller cuts at the dossier budget. */
    Optional<String> evidence(FindingClaim finding, EvidenceContext context);

    /** Read on every page load and by {@code get_finding}. */
    Optional<ClassifierDetail> detail(FindingRow finding);

    /**
     * On a finding, runs inside the ruling's transaction before the ruling write, so a throw rolls it back. On a case,
     * runs once on the newest finding with no transaction, before the case closes. Must be idempotent.
     */
    void absorbed(FindingRow finding, @Nullable String userId, String now);
}
