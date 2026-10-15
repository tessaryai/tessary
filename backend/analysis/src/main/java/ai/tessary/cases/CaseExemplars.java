// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.cases.CaseDtos.CaseExemplarView;
import ai.tessary.cases.CaseSubstrateRepository.Exemplar;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Every trace rendered is a {@code finding_evidence} row the classifier recorded; nothing is sampled at read time. A
 * finding whose classifier records no baseline shows an empty baseline, and older findings missing a role are not
 * backfilled.
 */
@Component
public class CaseExemplars {

    /**
     * The evidence set is uncapped, so this is the only presentational bound; the population size is on
     * {@code finding.evidence_counts}.
     */
    private static final int PER_ROLE = 5;

    /**
     * Stated rather than alphabetical, which would put {@code baseline} first. An unlisted role sorts last rather than
     * being dropped.
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
     * @param evidence read by the caller so a page of cases costs one query. Session-grain rows with no trace are
     * skipped.
     */
    public List<CaseExemplarView> forCase(String projectId, List<FindingEvidenceRow> evidence) {
        if (evidence.isEmpty()) return List.of();

        // Capped before hydration, so 50 pinned baselines don't become a 250-row IN clause.
        Map<String, List<FindingEvidenceRow>> byRole = new LinkedHashMap<>();
        for (FindingEvidenceRow row : evidence) {
            // Bound once: two calls are two values to the nullness analysis.
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
            // An aged-out trace is dropped rather than rendered as a link that 404s.
            Exemplar e = hydrated.get(row.traceId());
            if (e == null) continue;
            out.add(new CaseExemplarView(
                    e.traceId(), row.role(), row.rank(), e.name(), null, e.durationMs(), e.costUsd(), e.startedAt()));
        }
        return out;
    }
}
