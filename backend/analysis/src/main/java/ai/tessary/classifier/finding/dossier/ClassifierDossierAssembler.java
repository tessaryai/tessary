// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding.dossier;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Per-classifier-shape dossier assemblers, replacing {@code DossierPayload.forAgent}'s
 * strip-only pass for the classifiers whose payload has enough structure to build a genuinely richer
 * evidence section from — implementing the revised contract {@code DossierPayload}'s class javadoc
 * states: <b>complete enumeration when the evidence set is small, or a declared deterministic
 * selection rule plus population counts when it is not — never an undeclared sample.</b>
 *
 * <h2>Dispatch is by PAYLOAD SHAPE, not by a classifier-key allowlist</h2>
 *
 * <p>Frustration is recognised by the {@code cause_kind} its finding writer records in the payload, which a
 * rename of the classifier does not change either.
 *
 * <p>A key allowlist silently stops firing the moment a project renames or forks a built-in classifier,
 * or a new one reuses an existing detector's evidence shape under a different key. The detector
 * families this covers ({@code tool_error} and {@code metric_drift}, plus frustration above) write
 * payloads with a stable, documented internal shape
 * ({@code ToolErrorEvidence.toJson}, {@code MetricFindingEvidence.toJson}) — reading THAT is what stays
 * true across a rename. A payload matching none of the known shapes falls through to {@code
 * DossierPayload.forAgent}, unchanged: this class is additive, never a replacement for a classifier it
 * does not recognise.
 *
 * <h2>The token budget</h2>
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

    /** Matches {@code McpToolRegistry.TRACE_SPAN_CAP}'s order of magnitude, so there is one number to remember. */
    public static final int SMALL_EVIDENCE_SET_CAP = 200;

    /**
     * Empty when the shape has no dedicated assembler; the caller falls back to {@code DossierPayload.forAgent}. @param
     * evidence read only when enumeration needs rows (a page of at most {@link #SMALL_EVIDENCE_SET_CAP}+1) @param
     * evidenceCounts the finding row's cached counts, so this and {@code finding.md} cannot disagree
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
        if (FrustrationDossier.matches(root)) {
            body = FrustrationDossier.build(root);
        } else if (root.has("patterns") || root.has("failures")) {
            body = ToolErrorDossier.build(root);
        } else if (root.has("bucket") && (root.has("ratio") || root.has("w1_log"))) {
            body = MetricDriftDossier.build(root);
        } else {
            return Optional.empty();
        }

        return Optional.of(budget(body + "\n" + enumeration(evidence, projectId, findingId, evidenceCounts)));
    }

    /**
     * Every row when the set is small, else a declared bounded selection with per-role population. Public so an
     * adapter's {@code evidence.md} lists the same page.
     */
    public static String enumeration(
            FindingEvidenceRepository evidence, String projectId, String findingId, EvidenceCounts evidenceCounts) {
        return EvidenceEnumeration.section(evidence, projectId, findingId, evidenceCounts);
    }

    /**
     * Cuts at the last full line inside budget and always states the truncation with real character counts: a silently
     * clipped file is an undeclared sample.
     */
    public static String budget(String full) {
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
