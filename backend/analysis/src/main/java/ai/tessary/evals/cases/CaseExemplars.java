// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

import ai.tessary.evals.cases.CaseDtos.CaseExemplarView;
import ai.tessary.evals.cases.CaseSubstrateRepository.Exemplar;
import ai.tessary.evals.classifier.finding.FindingEvidenceRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The traces a case hands a reader: the references its finding pinned, rendered (launch requirement
 * E2).
 *
 * <p><b>Why a case needs this at all.</b> Every other zone on the page is a claim ABOUT traffic — a
 * ratio, a rate, a paragraph of triage prose. None of them is a turn anyone can read. A reader
 * with no prior context, which is exactly who this page is for, has no way to find out what "1.40×
 * slower" felt like without going to Traces and reconstructing the filter by hand.
 *
 * <h2>Everything here was recorded; nothing is sampled</h2>
 *
 * <p>Every trace this renders is a {@code finding_evidence} row — a reference the classifier wrote down
 * in the transaction that opened or bumped the finding, while the row it points at was still guaranteed
 * to exist. The roles come through untouched: {@code baseline} is a member of the population the
 * classifier compared AGAINST, {@code exemplar} is the member a Layer-2 escalation was pointed at,
 * {@code member} and {@code witness} are further instances, {@code changepoint} is where a rule's
 * violation was first seen.
 *
 * <p><b>What used to be here, and why it is gone.</b> The before-side was previously SAMPLED at read
 * time: a reference window is persisted as a sketch, its trace ids are not stored, so the page went back
 * to the substrate and pulled a few traces from the same scope over the hours before the onset. That
 * meant re-deriving the detector's scope in the case surface — including folding every tool name in the
 * project through the detector's bucketing function to find out which raw names one bucket key covered —
 * and it produced an illustration the page then had to disclaim. Both are gone. A finding whose
 * classifier records no baseline shows an empty baseline, which is the honest state and reads as one.
 *
 * <p>A finding written before its classifier started recording a role simply has no rows under it. That
 * is not an error and is not backfilled: the traces that would have been under it were never identified,
 * and inventing them at read time is the thing this change removes.
 */
@Component
public class CaseExemplars {

    /**
     * How many traces one role may put on the page. The evidence set itself is UNCAPPED — a detector
     * records the whole population it measured — so this is the only bound between a six-figure
     * {@code member} set and the case page, and it is a purely presentational one: enough to see a
     * pattern, few enough that nobody scrolls. What lands here is therefore a SAMPLE of the claim, and
     * the size it was drawn from is on {@code finding.evidence_counts} rather than derivable from these
     * rows — a reader that treats five traces as the population is reading a case page, not the claim.
     */
    private static final int PER_ROLE = 5;

    /**
     * Roles in reading order, which is NOT the column's own order.
     *
     * <p>Sorting on {@code role} sorts the vocabulary alphabetically, and alphabetically {@code
     * baseline} — the healthy side, the traces the classifier compared against — comes first. A reader
     * opening a case wants the thing that broke first and the thing it used to look like second, so the
     * order is stated here rather than inherited from the database. An unlisted role sorts last rather
     * than being dropped: a new role is new evidence, not an error.
     */
    private static final List<String> ROLE_ORDER = List.of(
            FindingEvidenceRow.Role.EXEMPLAR,
            FindingEvidenceRow.Role.CHANGEPOINT,
            FindingEvidenceRow.Role.WITNESS,
            FindingEvidenceRow.Role.MEMBER,
            FindingEvidenceRow.Role.BASELINE);

    private final CaseSubstrateRepository traces;

    public CaseExemplars(CaseSubstrateRepository traces) {
        this.traces = traces;
    }

    /**
     * Render one finding's evidence, in the order the reader wants it: the classifier's own role order
     * (by significance, as {@code FindingEvidenceRepository} defines it), then rank within a role.
     *
     * @param evidence the finding's rows, read by the caller so a page of cases costs one evidence query
     *     rather than one per case. Rows with no trace — a session-grain pin — are skipped here rather
     *     than rendered as a link that resolves to nothing; the finding surface shows them whole.
     */
    public List<CaseExemplarView> forCase(String projectId, List<FindingEvidenceRow> evidence) {
        if (evidence.isEmpty()) return List.of();

        // Ordered by role, capped per role, before a single trace is hydrated: the cap is what keeps a
        // finding pinning 50 baselines from costing the case page a 250-row IN clause.
        Map<String, List<FindingEvidenceRow>> byRole = new LinkedHashMap<>();
        for (FindingEvidenceRow row : evidence) {
            // Bound once rather than called twice: two calls are two values as far as any analysis is
            // concerned, so the null check would not cover the isBlank() beside it.
            String traceId = row.traceId();
            if (traceId == null || traceId.isBlank()) continue;
            List<FindingEvidenceRow> kept = byRole.computeIfAbsent(row.role(), k -> new ArrayList<>());
            if (kept.size() < PER_ROLE) kept.add(row);
        }
        List<FindingEvidenceRow> shown = new ArrayList<>();
        for (String role : ROLE_ORDER) shown.addAll(byRole.getOrDefault(role, List.of()));
        for (Map.Entry<String, List<FindingEvidenceRow>> e : byRole.entrySet()) {
            if (!ROLE_ORDER.contains(e.getKey())) shown.addAll(e.getValue());
        }
        if (shown.isEmpty()) return List.of();

        Map<String, Exemplar> hydrated = new LinkedHashMap<>();
        List<String> ids = shown.stream().map(FindingEvidenceRow::traceId).toList();
        for (Exemplar e : traces.byIds(projectId, ids)) hydrated.putIfAbsent(e.traceId(), e);

        List<CaseExemplarView> out = new ArrayList<>(shown.size());
        for (FindingEvidenceRow row : shown) {
            // A trace that has aged out from under a finding that outlived it is dropped rather than
            // rendered as a dead link: one fewer example beats a click that 404s.
            Exemplar e = hydrated.get(row.traceId());
            if (e == null) continue;
            out.add(new CaseExemplarView(
                    e.traceId(), row.role(), row.rank(), e.name(), null, e.durationMs(), e.costUsd(), e.startedAt()));
        }
        return out;
    }
}
