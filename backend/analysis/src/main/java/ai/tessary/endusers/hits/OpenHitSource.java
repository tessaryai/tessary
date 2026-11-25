// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.endusers.hits.HitRule.Completeness;
import ai.tessary.endusers.hits.HitRule.Reach;
import ai.tessary.endusers.hits.HitRule.Rows;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The hit rule of each open detector (plan 3.2). The rate detectors write every trial as {@code member} and the flagged
 * ones as {@code witness}, so a member is not a hit. Malformed output and the secret-leak facet keep 50 witnesses, so
 * their hits are read from their detection tables with the detector's own filters. A metric shift's hits are the members
 * of its shifted window, the traces it measured after the change.
 */
@Component
public class OpenHitSource implements EndUserHitSource {

    private static final List<Set<String>> WITNESS = List.of(Set.of(Role.WITNESS));

    static final HitRule TOOL_ERROR =
            new HitRule(Kind.TOOL_ERROR, Rows.EVIDENCE, WITNESS, false, Reach.MEMBER, Completeness.COMPLETE);

    static final HitRule FRUSTRATION =
            new HitRule(Kind.FRUSTRATION, Rows.EVIDENCE, WITNESS, false, Reach.MEMBER, Completeness.COMPLETE);

    static final HitRule GROUNDEDNESS =
            new HitRule(Kind.GROUNDEDNESS, Rows.EVIDENCE, WITNESS, true, Reach.MEMBER, Completeness.COMPLETE);

    static final HitRule ARMED_WINDOW = new HitRule(
            FindingRow.Cause.ARMED_WINDOW,
            Rows.EVIDENCE,
            List.of(Set.of(Role.MEMBER)),
            false,
            Reach.CALL_SITE,
            Completeness.COMPLETE);

    static final HitRule MALFORMED_OUTPUT = new HitRule(
            Kind.MALFORMED_OUTPUT,
            Rows.MALFORMED_OUTPUT_DETECTIONS,
            WITNESS,
            false,
            Reach.CALL_SITE,
            Completeness.COMPLETE);

    static final HitRule SECRET_LEAK = new HitRule(
            Kind.SECRET_LEAK, Rows.SECRET_LEAK_DETECTIONS, WITNESS, false, Reach.CALL_SITE, Completeness.COMPLETE);

    static final HitRule METRIC_DRIFT = new HitRule(
            "metric_drift",
            Rows.EVIDENCE,
            List.of(Set.of(Role.MEMBER)),
            false,
            Reach.CALL_SITE,
            Completeness.METRIC_SHIFT);

    @Override
    public Optional<HitRule> ruleFor(HitFinding finding) {
        return switch (finding.classifierKey()) {
            case Kind.TOOL_ERROR -> Optional.of(TOOL_ERROR);
            case Kind.FRUSTRATION -> Optional.of(FRUSTRATION);
            case Kind.GROUNDEDNESS -> Optional.of(GROUNDEDNESS);
            case Kind.MALFORMED_OUTPUT -> Optional.of(MALFORMED_OUTPUT);
            case Kind.SECRET_LEAK -> Optional.of(SECRET_LEAK);
            case Kind.DURATION_DRIFT, Kind.COST_DRIFT -> Optional.of(METRIC_DRIFT);
            default ->
                FindingRow.Cause.ARMED_WINDOW.equals(finding.causeKind()) && !finding.faceted()
                        ? Optional.of(ARMED_WINDOW)
                        : Optional.empty();
        };
    }
}
