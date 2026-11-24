// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import ai.tessary.endusers.hits.EndUserHitRepository.Finding;
import ai.tessary.endusers.hits.EndUserHitRepository.HitRow;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** A finding's stored hit rows read under its hit rule. */
@Service
public class HitReads {

    private final EndUserHitRepository hits;
    private final HitRules rules;

    public HitReads(EndUserHitRepository hits, HitRules rules) {
        this.hits = hits;
        this.rules = rules;
    }

    /**
     * @param hits the rows of the hit roles
     * @param hitRefs the refs that count as hits
     * @param memberRefs the refs of the member rows, which are the reach when the rule's reach is {@code MEMBER}
     */
    public record FindingHits(HitRule rule, List<HitRow> hits, long hitRefs, long memberRefs) {}

    public FindingHits findingHits(String projectId, String findingId) {
        Finding finding = hits.findings(projectId, List.of(findingId)).stream()
                .findFirst()
                .orElseThrow(() -> new TessaryException(ClassifierError.FINDING_NOT_FOUND, findingId));
        HitRule rule = rules.ruleFor(finding.hitFinding());
        List<HitRow> stored = hits.stored(projectId, findingId);
        Set<String> hitRoles = rule.hitRoles(stored.stream().map(HitRow::role).collect(Collectors.toSet()));
        List<HitRow> hitRows =
                stored.stream().filter(r -> hitRoles.contains(r.role())).toList();
        return new FindingHits(
                rule,
                hitRows,
                hitRows.stream().mapToLong(HitRow::refs).sum(),
                stored.stream()
                        .filter(r -> Role.MEMBER.equals(r.role()))
                        .mapToLong(HitRow::refs)
                        .sum());
    }
}
