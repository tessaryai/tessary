// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A full adapter, so {@link FindingService} knows no table and a third store is an implementation, not more branches.
 * Registration order ({@code @Order}) is the wire order. On every {@code Optional} method return empty for an id this
 * source does not own, including one the flag layer withholds; throw a domain error only once ownership is established.
 */
public interface TriageSource {

    String kind();

    /** Strongest first, because the caller truncates to a budget. */
    List<Escalatable> listAutoEscalatable(String projectId, long minObservations, int limit);

    /** The same method behind the button and {@link TriageAutoEscalator}. */
    Optional<BehaviorAnalysisView> analyze(String projectId, String findingId);

    /**
     * A source this page's narrowing excludes returns an empty list: which filters apply is the store's own knowledge.
     */
    List<BehaviorFindingView> list(
            String projectId,
            @Nullable String status,
            @Nullable String callSiteId,
            @Nullable String detector,
            boolean confirmedOnly);

    /**
     * Deliberately not the mirror of {@link #resolve}: a shared-table row keyed to another source's classifier is
     * disclaimed here but claimed there.
     */
    Optional<BehaviorFindingDetailView> detail(String projectId, String findingId);

    /** Runs inside the caller's transaction. */
    Optional<BehaviorFindingView> resolve(String projectId, String findingId, String action, @Nullable String userId);

    /** Empty also when the finding was resolved while the job waited; both mean nothing to rule on. */
    Optional<TriageBrief> brief(BehaviorTriageJobRow job);

    /** Called only on the source whose {@link #brief} produced the run. */
    void recordVerdict(
            String projectId,
            String findingId,
            BehaviorTriageVerdict verdict,
            @Nullable String citationsJson,
            String now);

    record Escalatable(String findingId) {}
}
