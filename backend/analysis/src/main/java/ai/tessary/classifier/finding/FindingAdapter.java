// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.dossier.EvidenceContext;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Claims the findings a classifier outside the built-in catalog filed in the shared {@code finding} table, so they
 * get what a built-in's findings get: Layer-2 escalation ({@code BehaviorTriageSource.analyze} and
 * {@code listAutoEscalatable}), a {@code method.md} in the triage dossier ({@code BehaviorTriageEngine.methodCard}),
 * a {@code method.md} and an {@code evidence.md} in the RCA dossier ({@code RcaAnalysisService}), and a
 * detector-state write when a person absorbs one ({@code BehaviorTriageSource.resolve} and {@code repin}).
 *
 * <p>Consumers reach adapters through {@link FindingAdapters} only. The open edition ships none, and then every
 * finding is handled exactly as before.
 */
public interface FindingAdapter {

    /**
     * Whether this adapter's classifier filed the finding. Answers the same for every finding of one project and
     * classifier key: escalation asks it once per key, not once per finding.
     */
    boolean owns(FindingClaim finding);

    /**
     * The text of the triage dossier's {@code method.md} for an owned finding; empty when the classifier has none.
     * Takes the claim only, so no ruling reaches a dossier through it.
     */
    Optional<String> triageMethod(FindingClaim finding);

    /**
     * The text of the RCA dossier's {@code method.md} for an owned finding; empty when the classifier has none. Takes
     * the claim only, so no ruling reaches the RCA dossier through it.
     */
    Optional<String> rcaMethod(FindingClaim finding);

    /**
     * The text of the RCA dossier's {@code evidence.md} for an owned finding, or empty to keep the shape assembler and
     * the fenced payload. The caller cuts the text at the dossier budget and states the cut.
     */
    Optional<String> evidence(FindingClaim finding, EvidenceContext context);

    /**
     * The detector-state half of a person's "Legitimate, absorb" on an owned finding or its case.
     *
     * <p>On a finding ({@code FindingService.resolve}) it runs inside the ruling's transaction and before the ruling
     * write, so a throw rolls the ruling back and the finding stays open. On a case ({@code CaseService.absorb} to
     * {@code BehaviorTriageSource.repinCase}) it runs once, on the case's newest finding, with no transaction: it
     * commits on its own before the case closes, and a throw leaves the case open. It must be idempotent.
     *
     * @param now the caller's clock as an ISO-8601 instant, shared with the ruling it goes with
     */
    void absorbed(FindingRow finding, @Nullable String userId, String now);
}
