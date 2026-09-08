// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding.dossier;

import ai.tessary.evals.classifier.finding.FindingEvidenceRepository;
import ai.tessary.evals.classifier.finding.FindingEvidenceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * D (#994): per-classifier-shape dossier assemblers, replacing {@code DossierPayload.forAgent}'s
 * strip-only pass for the classifiers whose payload has enough structure to build a genuinely richer
 * evidence section from — implementing the revised contract {@code DossierPayload}'s class javadoc
 * states: <b>complete enumeration when the evidence set is small, or a declared deterministic
 * selection rule plus population counts when it is not — never an undeclared sample.</b>
 *
 * <h2>Dispatch is by PAYLOAD SHAPE, not by a classifier-key allowlist</h2>
 *
 * <p>A key allowlist silently stops firing the moment a project renames or forks a built-in classifier,
 * or a new one reuses an existing detector's evidence shape under a different key. The three detector
 * families this covers ({@code tool_error}, {@code metric_drift}, and any classifier that publishes a
 * {@code window} block) write payloads with a stable, documented internal shape
 * ({@code ToolErrorEvidence.toJson}, {@code MetricFindingEvidence.toJson}) — reading THAT is what stays
 * true across a rename. A payload matching none of the three known shapes falls through to {@code
 * DossierPayload.forAgent}, unchanged: this class is additive, never a replacement for a classifier it
 * does not recognise.
 *
 * <h2>The token budget (D.3)</h2>
 *
 * <p>Every assembled dossier is capped at {@link #CHAR_BUDGET} — a ~30k-token budget converted via a
 * documented ~4-chars/token heuristic (English prose/JSON averages close to that; being off by 20% here
 * costs nothing, since the real enforcement is the hard character cap, not the token estimate). Only the
 * ENUMERATION section (the part whose size scales with evidence volume) is subject to truncation; the
 * claim, the statistics and the population totals are never cut — a truncated dossier must still be
 * able to state what fired and how much evidence exists, even when it cannot list all of it.
 */
public final class ClassifierDossierAssembler {

    private ClassifierDossierAssembler() {}

    /** ~30k tokens at ~4 chars/token — see the class javadoc for why this is a soft, documented estimate. */
    static final int CHAR_BUDGET = 30_000 * 4;

    /**
     * The "small enough to enumerate whole" line — matches {@code McpToolRegistry.TRACE_SPAN_CAP}'s
     * order of magnitude, on the same reasoning: a page of this size is cheap to read completely, and a
     * different constant here would just be a second number someone has to remember agrees with the
     * first.
     */
    public static final int SMALL_EVIDENCE_SET_CAP = 200;

    /**
     * Assemble a dossier evidence file for {@code payloadJson}, or empty when its shape is not one this
     * class has a dedicated assembler for — the caller falls back to {@code DossierPayload.forAgent} in
     * that case (see the class javadoc's dispatch note).
     *
     * @param evidence the finding's evidence table, read only when the enumeration section actually
     *     needs rows (a page bounded at {@link #SMALL_EVIDENCE_SET_CAP}+1, never the whole population)
     * @param projectId / @param findingId the evidence table's own composite key
     * @param evidenceCounts per-role counts already cached on the finding row ({@code
     *     FindingRow#evidenceCount}) — read here rather than re-derived, so this and the finding's own
     *     {@code finding.md} section can never disagree about how much evidence exists
     */
    public static Optional<String> assemble(
            ObjectMapper mapper,
            FindingEvidenceRepository evidence,
            String projectId,
            String findingId,
            EvidenceCounts evidenceCounts,
            @Nullable String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Optional.empty();
        JsonNode root;
        try {
            root = mapper.readTree(payloadJson);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!root.isObject()) return Optional.empty();

        String body;
        if (root.has("patterns") || root.has("failures")) {
            body = ToolErrorDossier.build(root);
        } else if (root.has("bucket") && (root.has("ratio") || root.has("w1_log"))) {
            body = MetricDriftDossier.build(root);
        } else if (root.has("window")) {
            body = WindowFindingDossier.build(root);
        } else {
            return Optional.empty();
        }

        String enumeration = EvidenceEnumeration.section(evidence, projectId, findingId, evidenceCounts);
        return Optional.of(budget(body + "\n" + enumeration));
    }

    /**
     * Truncate the ENUMERATION section only, keeping the shape-specific summary above it whole. Cuts at
     * the last full line inside budget rather than mid-line, and always appends a stated truncation
     * note with the real character counts — a silently clipped file is exactly the undeclared-sample
     * failure D's contract exists to end, just moved from "which trace ids" to "how much of the dossier".
     */
    static String budget(String full) {
        if (full.length() <= CHAR_BUDGET) return full;
        int cut = full.lastIndexOf('\n', CHAR_BUDGET);
        if (cut < 0) cut = CHAR_BUDGET;
        return full.substring(0, cut)
                + String.format(
                        Locale.ROOT,
                        "\n\n[dossier truncated: %,d of %,d characters shown — the claim, statistics and"
                                + " population totals above this point are complete; only the tail of the"
                                + " enumeration list was cut. Page the remainder via get_finding_evidence.]\n",
                        cut,
                        full.length());
    }

    /** Per-role counts, read once and passed to both the shape-specific body and the enumeration. */
    public record EvidenceCounts(long exemplar, long member, long baseline, long witness, long changepoint) {
        public long total() {
            return exemplar + member + baseline + witness + changepoint;
        }

        public long forRole(String role) {
            return switch (role) {
                case FindingEvidenceRow.Role.EXEMPLAR -> exemplar;
                case FindingEvidenceRow.Role.MEMBER -> member;
                case FindingEvidenceRow.Role.BASELINE -> baseline;
                case FindingEvidenceRow.Role.WITNESS -> witness;
                case FindingEvidenceRow.Role.CHANGEPOINT -> changepoint;
                default -> 0;
            };
        }
    }
}
