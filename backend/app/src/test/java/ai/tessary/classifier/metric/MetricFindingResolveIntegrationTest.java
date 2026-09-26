// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorBaselineEventRepository;
import ai.tessary.classifier.finding.BehaviorBaselineEventRow;
import ai.tessary.classifier.finding.BehaviorDtos;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The correction loop for a {@code distribution_shift} finding (metric-drift.md §9): both verbs correct the reference
 * a distribution is measured against.
 *
 * <p>The asymmetry is the point. A reference that moved on Real deviation would compare a broken system with itself:
 * no shift, the case auto-closed, and a confirmed regression reading as a recovery everywhere. So "the reference did
 * not move" is load-bearing.
 */
@SpringBootTest
class MetricFindingResolveIntegrationTest {

    /** Before anything the fixtures stamp, so a finding written twice reads as one running spell. */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

    @Autowired
    FindingService drift;

    @Autowired
    FindingRepository findings;

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    BehaviorBaselineEventRepository events;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    TenantService tenants;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The closed window the pinned reference should end up holding. */
    private static final String CLOSED_SKETCH = "{\"kind\":\"hist\",\"lo\":1.0,\"r\":1.05,\"bins\":320,\"n\":500}";

    /** The window still filling: deliberately different, and never what gets pinned. */
    private static final String FILLING_SKETCH = "{\"kind\":\"hist\",\"lo\":1.0,\"r\":1.05,\"bins\":320,\"n\":9}";

    private static final String CLOSED_WORKLOAD = "{\"kind\":\"workload\",\"input_tokens\":{\"n\":500}}";

    /** The control ring with the closed window as its only day; absorbing pins this day. */
    private static final String CONTROL_RING =
            "{\"kind\":\"control\",\"half_life_days\":7.0,\"days\":[{\"d\":\"2026-07-23\",\"m\":" + CLOSED_SKETCH
                    + ",\"w\":" + CLOSED_WORKLOAD + "}]}";

    /**
     * §6's key as a human reads it: ClassifiersPage renders it verbatim, so it must be a readable sentence, not an
     * opaque id.
     */
    private static final String CAUSE_KEY = "turn_duration:discover-sales-prospects:slower:pinned";

    @Test
    @DisplayName("Legitimate — absorb: the reference moves onto the closed window and the changelog says so")
    void expectedRepinsTheReferenceAndWritesAChangelogRow() {
        Fixture f = fixture("metric-resolve-absorb");

        assertNull(
                baselines.findById(f.projectId, f.baselineId).orElseThrow().pinnedSketchJson(),
                "nothing is pinned before the human presses anything");

        var view = drift.resolve(f.projectId, f.findingId, BehaviorDtos.BehaviorResolutionRequest.EXPECTED, "user-1");

        MetricBaselineRow row = baselines.findById(f.projectId, f.baselineId).orElseThrow();
        // §9 says {@code pinned_sketch <- current}, but {@code current_sketch_json} holds the filling window: pinning
        // it would install a nine-sample reference the detector abstains on. The newest complete summary is the
        // ring's newest day.
        assertEquals(CLOSED_SKETCH, row.pinnedSketchJson(), "the reference is the closed window, not the filling one");
        assertEquals(CLOSED_WORKLOAD, row.pinnedWorkloadJson(), "the workload moves WITH the sketch it belongs to");
        assertNotNull(row.pinnedAt(), "an absorbed reference is dated by the decision that installed it");
        assertEquals("pv-deploy-9", row.pinnedByVersionId());
        assertEquals(FILLING_SKETCH, row.currentSketchJson(), "absorbing reads the window; it does not consume it");

        assertEquals(FindingRow.Status.CLOSED, view.status(), "the absorbed cause closes");
        assertEquals(FindingRow.TriageVerdict.NEGATIVE, view.triageVerdict());

        // Every absorption is a durable changelog row, and a person's choice most needs to be readable later.
        List<BehaviorBaselineEventRow> log = changelogFor(f.projectId, f.baselineId);
        assertEquals(1, log.size(), "one re-pin, one changelog entry");
        assertEquals(
                BehaviorBaselineEventRow.Event.BASELINE_REPINNED, log.get(0).event());
        assertEquals(CAUSE_KEY, log.get(0).gramKey(), "the entry says what it was about, in the finding's own words");
        // Read as JSON: Postgres re-serializes jsonb, so a substring check would break on an upgrade.
        assertNotNull(log.get(0).detailJson(), "the finding's evidence rides along as the entry's detail");
        assertEquals("turn_duration", detail(log.get(0)).path("measure").asText());
        assertEquals(1.4, detail(log.get(0)).path("ratio").asDouble(), 1e-9);
        assertNull(log.get(0).profileId(), "a metric event hangs off a baseline, never off a drift profile");
    }

    @Test
    @DisplayName("Real deviation: the reference does NOT move, so the next window still sees the regression")
    void notExpectedLeavesTheReferenceExactlyWhereItWas() {
        Fixture f = fixture("metric-resolve-deviation");

        var view =
                drift.resolve(f.projectId, f.findingId, BehaviorDtos.BehaviorResolutionRequest.NOT_EXPECTED, "user-1");

        MetricBaselineRow row = baselines.findById(f.projectId, f.baselineId).orElseThrow();
        assertNull(row.pinnedSketchJson(), "confirming a regression must never install it as the new normal");
        assertNull(row.pinnedWorkloadJson());
        assertNull(row.pinnedAt());
        assertNull(row.pinnedByVersionId());

        // A positive human ruling keeps the finding open with human_verdict_at stamped, which lets it open or join a
        // case.
        assertEquals(FindingRow.Status.OPEN, view.status());
        assertEquals(FindingRow.TriageVerdict.POSITIVE, view.triageVerdict());
        assertNotNull(view.humanVerdictAt(), "the ruling is stamped, which is what a case is opened off");

        assertEquals(
                List.of(),
                changelogFor(f.projectId, f.baselineId),
                "nothing moved, so the changelog has nothing to record — a row here would claim otherwise");
    }

    private record Fixture(String projectId, String baselineId, String findingId) {}

    private Fixture fixture(String slug) {
        return fixture(slug, Instant.now().toString());
    }

    /**
     * {@code eventAt} is the onset in event time [R11], separate from wall-clock {@code now} so the test can tell
     * them apart.
     */
    private Fixture fixture(String slug, String eventAt) {
        String projectId = TenantFixture.bootstrap(tenants, slug).project().id();
        classifiers.seedBuiltIns(projectId);
        String classifierId = ClassifierRows.byKey(signals, projectId, BuiltInDetector.Kind.DURATION_DRIFT)
                .orElseThrow()
                .id();
        String now = Instant.now().toString();

        String baselineId = baselines
                .ensure(new MetricBaselineRow(
                        Ids.ulid(),
                        projectId,
                        classifierId,
                        Measure.TURN_DURATION,
                        BucketKind.CALL_SITE,
                        "discover-sales-prospects",
                        State.ARMED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        now,
                        now))
                .id();
        // A day of closed windows and a part-filled next one, as any real bucket has when a human looks; absorbing
        // pins the newest closed day.
        baselines.closeWindow(baselineId, CONTROL_RING, now);
        baselines.advanceWindow(baselineId, 9, now, "2026-07-24T00:00:00Z", null, null, null);
        baselines.updateCurrentSketch(baselineId, FILLING_SKETCH, null, null, null, now);

        String findingId = findings.recordShift(
                        Ids.ulid(),
                        projectId,
                        classifierFor(CAUSE_KEY),
                        baselineId,
                        CAUSE_KEY,
                        500,
                        "pv-deploy-9",
                        "discover-sales-prospects",
                        "{\"measure\":\"turn_duration\",\"ratio\":1.4,\"w1_log\":0.34}",
                        eventAt,
                        Instant.parse(eventAt).minus(QUIET_WINDOW).toString(),
                        now)
                .findingId();
        return new Fixture(projectId, baselineId, findingId);
    }

    private static JsonNode detail(BehaviorBaselineEventRow event) {
        try {
            return MAPPER.readTree(event.detailJson());
        } catch (JsonProcessingException e) {
            throw new AssertionError("changelog detail is not JSON: " + event.detailJson(), e);
        }
    }

    private List<BehaviorBaselineEventRow> changelogFor(String projectId, String baselineId) {
        return events.listByProject(projectId, 100).stream()
                .filter(e -> baselineId.equals(e.baselineId()))
                .toList();
    }

    /** Mirrors the sweep's mapping; hardcoding one key would make the detector filter pass by construction. */
    private static String classifierFor(String causeKey) {
        return causeKey.startsWith("cost:") ? BuiltInDetector.Kind.COST_DRIFT : BuiltInDetector.Kind.DURATION_DRIFT;
    }
}
