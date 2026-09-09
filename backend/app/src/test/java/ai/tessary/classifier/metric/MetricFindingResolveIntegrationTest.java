// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The correction loop for a {@code distribution_shift} finding — PROGRAM.md §9, the half of the
 * metric-drift program a human actually touches.
 *
 * <p>Both verbs already exist on the Classifiers page and already post the same two action strings that
 * behaviour drift uses, so nothing about this is visible in the frontend diff. What changes is what the
 * strings <em>mean</em>: behaviour drift corrects a gram, metric drift corrects the <b>reference</b> a
 * whole distribution is measured against.
 *
 * <p><b>The asymmetry is the point, and it is why both branches are tested rather than just the happy
 * one.</b> A reference that moved on <em>Real deviation</em> would make the next window compare a broken
 * system against its broken self: no shift, case auto-closed, and a regression a human had personally
 * confirmed reading as a recovery on every surface in the product. The detector would go silent through
 * exactly the event it exists to catch. So "the reference did NOT move" is a load-bearing assertion, not
 * a symmetry check.
 */
@SpringBootTest
class MetricFindingResolveIntegrationTest {

    /**
     * A horizon comfortably before anything these fixtures stamp, so a finding written twice reads as ONE
     * spell still running rather than as a recovery and a re-fire — the production behaviour these tests
     * are about. A test that wants the other arm passes its own.
     */
    private static final Duration QUIET_WINDOW = Duration.ofDays(1);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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

    /** The window the bucket sat in before it moved — what the pinned reference should end up holding. */
    private static final String CLOSED_SKETCH = "{\"kind\":\"hist\",\"lo\":1.0,\"r\":1.05,\"bins\":320,\"n\":500}";

    /** The window still being FILLED. Deliberately different, and deliberately NOT what gets pinned. */
    private static final String FILLING_SKETCH = "{\"kind\":\"hist\",\"lo\":1.0,\"r\":1.05,\"bins\":320,\"n\":9}";

    private static final String CLOSED_WORKLOAD = "{\"kind\":\"workload\",\"input_tokens\":{\"n\":500}}";

    /**
     * The rolling control as {@link ai.tessary.classifier.metric.MetricControl} serializes it, with
     * the closed window as its newest — and only — day. Absorbing pins THIS day, so the assertions below
     * are the same ones they were when the reference was a single {@code prev} slot.
     */
    private static final String CONTROL_RING =
            "{\"kind\":\"control\",\"half_life_days\":7.0,\"days\":[{\"d\":\"2026-07-23\",\"m\":" + CLOSED_SKETCH
                    + ",\"w\":" + CLOSED_WORKLOAD + "}]}";

    /**
     * The user-visible form of PROGRAM.md §6's key. {@code ClassifiersPage.tsx} renders it verbatim in
     * mono followed by a literal {@code " — {causeKind}"}, so this string is a sentence a human reads and
     * not an internal identifier — which is what rules out a compact opaque key. Pinned here because the
     * page would happily render an unreadable one.
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
        // PROGRAM.md §9 writes this as `pinned_sketch <- current`, and the column literally named
        // current_sketch_json is the wrong one to read: it holds the window still being filled, so
        // pinning it would install a nine-sample reference that the detector then abstains on until
        // something else replaces it. The newest COMPLETE summary is the control ring's newest day,
        // which holds the window the finding fired on.
        assertEquals(CLOSED_SKETCH, row.pinnedSketchJson(), "the reference is the closed window, not the filling one");
        assertEquals(CLOSED_WORKLOAD, row.pinnedWorkloadJson(), "the workload moves WITH the sketch it belongs to");
        assertNotNull(row.pinnedAt(), "an absorbed reference is dated by the decision that installed it");
        assertEquals("pv-deploy-9", row.pinnedByVersionId());
        assertEquals(FILLING_SKETCH, row.currentSketchJson(), "absorbing reads the window; it does not consume it");

        assertEquals(FindingRow.Status.ALLOWLISTED, view.status(), "the absorbed cause stops recurring");

        // An online baseline cannot be stopped from absorbing drift. What can be done is to make every
        // absorption a durable, readable row — and this is the one absorption a person chose, so it is
        // the one that most needs to be readable a quarter later.
        List<BehaviorBaselineEventRow> log = changelogFor(f.projectId, f.baselineId);
        assertEquals(1, log.size(), "one re-pin, one changelog entry");
        assertEquals(
                BehaviorBaselineEventRow.Event.BASELINE_REPINNED, log.get(0).event());
        assertEquals(CAUSE_KEY, log.get(0).gramKey(), "the entry says what it was about, in the finding's own words");
        // Read as JSON, not as text: `evidence` and `detail` are both jsonb, and Postgres re-serializes
        // a jsonb value on the way out — keys reordered, a space after every colon. A substring assertion
        // on the blob would pass today and break on a Postgres upgrade for no real reason.
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

        // BLOCKED is what "marks for escalation" means concretely: it stamps human_verdict_at, so
        // recurrences_since_verdict starts counting the windows that shifted after a person said this
        // must not happen — the state PLAN.md §8's CaseSource reads.
        assertEquals(FindingRow.Status.BLOCKED, view.status());
        assertNotNull(view.humanVerdictAt(), "the ruling is stamped, which is what a case is opened off");

        assertEquals(
                List.of(),
                changelogFor(f.projectId, f.baselineId),
                "nothing moved, so the changelog has nothing to record — a row here would claim otherwise");
    }

    @Test
    @DisplayName("absorbing does not write an allowlist row: there is no gram to allow forever")
    void absorbingWritesNoAllowlistRow() {
        Fixture f = fixture("metric-resolve-no-allowlist");
        drift.resolve(f.projectId, f.findingId, BehaviorDtos.BehaviorResolutionRequest.EXPECTED, "user-1");

        // An allowlist row is keyed on a PROFILE and says "this symbol is fine forever", which has no
        // meaning for a distribution whose reference moves — and behavior_allowlist's own cause CHECK was
        // deliberately left un-widened in 0042 so that a distribution_shift reaching it fails loudly. The
        // re-pin IS the correction; a second one would be a second, contradictory record of it.
        assertFalse(
                events.listByProject(f.projectId, 100).stream()
                        .anyMatch(e -> BehaviorBaselineEventRow.Event.GRAM_ALLOWLISTED.equals(e.event())),
                "the metric branch corrects a reference, not a gram");
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

    /** A project holding one armed baseline with a closed window, and one open finding against it. */
    private record Fixture(String projectId, String baselineId, String findingId) {}

    private Fixture fixture(String slug) {
        String projectId = TenantFixture.bootstrap(tenants, slug).project().id();
        classifiers.seedBuiltIns(projectId);
        String classifierId = signals.findByKey(projectId, BuiltInDetector.Kind.DURATION_DRIFT)
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
        // A day's worth of closed windows in the control ring, and the next window already part-filled —
        // the state any real bucket is in by the time a human looks at its finding. Absorbing pins the
        // ring's NEWEST day, never the filling window, which is what the assertions below pin down.
        baselines.closeWindow(baselineId, CONTROL_RING, "2026-07-24T00:00:00Z", FILLING_SKETCH, null, null, 9, now);

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
                        Instant.parse(now).minus(QUIET_WINDOW).toString(),
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

    /**
     * Which classifier a measure files under. The sweep spells the same mapping; a test that hardcoded
     * one key would make the detector filter pass by construction.
     */
    private static String classifierFor(String causeKey) {
        return causeKey.startsWith("cost:") ? BuiltInDetector.Kind.COST_DRIFT : BuiltInDetector.Kind.DURATION_DRIFT;
    }
}
