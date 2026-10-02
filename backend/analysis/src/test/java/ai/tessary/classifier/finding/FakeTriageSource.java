// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A hand-written adapter reads better than a mock script. */
class FakeTriageSource implements TriageSource {
    private final String kind;
    private final List<BehaviorFindingView> rows;
    private @Nullable TriageBrief brief;

    FakeTriageSource(String kind, BehaviorFindingView... rows) {
        this.kind = kind;
        this.rows = List.of(rows);
    }

    /** Claims every job, handing back a brief so the worker always calls {@code engine.rule}. */
    static FakeTriageSource briefing() {
        FakeTriageSource source = new FakeTriageSource("behavior");
        source.brief = new TriageBrief(Map.of("finding.md", "the finding"), "rule on it");
        return source;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public List<Escalatable> listAutoEscalatable(String projectId, long minObservations, int limit) {
        return List.of();
    }

    @Override
    public Optional<BehaviorAnalysisView> analyze(String projectId, String findingId) {
        return Optional.empty();
    }

    @Override
    public List<BehaviorFindingView> list(
            String projectId,
            @Nullable String status,
            @Nullable String callSiteId,
            @Nullable String detector,
            boolean confirmedOnly) {
        return rows;
    }

    @Override
    public Optional<BehaviorFindingDetailView> detail(String projectId, String findingId) {
        return Optional.empty();
    }

    @Override
    public Optional<BehaviorFindingView> resolve(
            String projectId, String findingId, String action, @Nullable String userId) {
        return Optional.empty();
    }

    @Override
    public Optional<TriageBrief> brief(BehaviorTriageJobRow job) {
        return Optional.ofNullable(brief);
    }

    @Override
    public void recordVerdict(
            String projectId,
            String findingId,
            BehaviorTriageVerdict verdict,
            @Nullable String citationsJson,
            String now) {
        // the recording half is the integration suite's
        assertNotNull(Instant.parse(now));
    }
}
