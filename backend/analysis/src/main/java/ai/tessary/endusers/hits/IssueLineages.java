// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.cases.CaseKey;
import ai.tessary.endusers.hits.IssueLineageRepository.CaseFinding;
import ai.tessary.open.hash.Sha256;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Resolves findings to issues. An issue is a case lineage: every case with one {@link CaseKey}. A ruling freezes a
 * finding and the next firing opens a fresh one, and a cause can open a fresh case with the same key, so the estimate
 * reads the union of every finding of the lineage. A finding with no case is in no lineage.
 */
@Service
public class IssueLineages {

    private static final int KEY_BYTES = 16;

    private final IssueLineageRepository lineages;
    private final HitRules rules;

    public IssueLineages(IssueLineageRepository lineages, HitRules rules) {
        this.lineages = lineages;
        this.rules = rules;
    }

    /**
     * @param lineageKey lower-case hex of the first 16 bytes of SHA-256 over the case key's parts
     * @param currentCaseId the newest unresolved case, else the newest case
     * @param caseIds oldest first
     * @param complete every finding's detector records every hit
     * @param metricShift the issue shifts a measure for all traffic and has no hit user IDs
     */
    public record IssueLineage(
            String lineageKey,
            CaseKey caseKey,
            String currentCaseId,
            List<String> caseIds,
            List<String> findingIds,
            boolean complete,
            boolean metricShift) {}

    /** Ordered by each lineage's first case. */
    public List<IssueLineage> lineages(String projectId) {
        Map<CaseKey, List<CaseFinding>> byKey = new LinkedHashMap<>();
        for (CaseFinding row : lineages.caseFindings(projectId)) {
            byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
        }
        List<IssueLineage> out = new ArrayList<>(byKey.size());
        byKey.forEach((key, rows) -> out.add(lineage(key, rows)));
        return out;
    }

    private IssueLineage lineage(CaseKey key, List<CaseFinding> rows) {
        CaseFinding current = rows.get(0);
        for (CaseFinding row : rows) {
            if (row.live() || !current.live()) current = row;
        }
        List<HitRule> findingRules =
                rows.stream().map(r -> rules.ruleFor(r.finding())).toList();
        return new IssueLineage(
                keyOf(key),
                key,
                current.caseId(),
                rows.stream().map(CaseFinding::caseId).distinct().toList(),
                rows.stream().map(CaseFinding::findingId).sorted().toList(),
                findingRules.stream().allMatch(HitRule::complete),
                findingRules.stream().allMatch(r -> r.completeness() == HitRule.Completeness.NOT_APPLICABLE));
    }

    static String keyOf(CaseKey key) {
        String joined = String.join("\0", key.detector(), key.subjectKind(), key.subjectId(), key.metric());
        return HexFormat.of().formatHex(Arrays.copyOf(Sha256.digest(joined), KEY_BYTES));
    }
}
