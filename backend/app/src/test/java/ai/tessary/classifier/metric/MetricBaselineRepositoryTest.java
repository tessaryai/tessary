// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code metric_baseline} against real Postgres, since the likely faults are invisible to a mock.
 *
 * <p>The scope key is an expression, and Postgres infers the {@code ON CONFLICT} arbiter by its text, so {@code
 * SCOPE_KEY} and {@code ux_metric_baseline_scope} must match exactly or the first sweep fails for every bucket. NULL
 * is a real scope: without {@code COALESCE(environment_id, '')} the index never fires for untagged rows and each pass
 * inserts another baseline. The {@code counted_through_*} guard was added after {@code trace_count} 565 was logged
 * for 443 distinct traces.
 */
@SpringBootTest
class MetricBaselineRepositoryTest {

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("ensure is idempotent: a replayed page returns the live row rather than inserting a second")
    void ensureIsIdempotentUnderAReplayedPage() {
        Scope scope = scope("baseline-ensure");

        MetricBaselineRow first = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a"));
        // A second pass mints a fresh id and must get the existing row: the sweep discovers buckets from traffic, so
        // "insert or read" is its only shape.
        MetricBaselineRow replayed = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a"));

        assertEquals(first.id(), replayed.id(), "the second ensure must return the FIRST row, not its own seed");
        assertEquals(1, countIn(scope), "one scope, one baseline");
        // DO UPDATE, not DO NOTHING, which suppresses RETURNING and costs a second round trip.
        assertEquals(State.LEARNING, replayed.state());
    }

    @Test
    @DisplayName("measure and bucket are both in the scope, so one call site's measures never share a row")
    void measureAndBucketAreBothInTheScope() {
        Scope scope = scope("baseline-scope");

        String duration =
                baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a")).id();
        String cost = baselines.ensure(seed(scope, Measure.COST, "cs-a")).id();
        String otherBucket =
                baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-b")).id();

        assertNotEquals(duration, cost, "seconds and dollars are not the same distribution");
        assertNotEquals(duration, otherBucket);
        assertEquals(3, countIn(scope));
        assertEquals(
                3,
                baselines.listByClassifier(scope.projectId, scope.classifierId).size());
    }

    @Test
    @DisplayName("advanceWindow moves the counter and the watermark that guards it in one write")
    void advanceWindowMovesCounterAndWatermarkTogether() {
        Scope scope = scope("baseline-advance");
        String id = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a")).id();

        baselines.advanceWindow(
                id, 120, now(), "2026-07-20T10:00:00Z", "2026-07-20T11:00:00Z", "2026-07-20T11:00:05Z", "obs-1");
        baselines.advanceWindow(
                id, 80, now(), "2026-07-20T12:00:00Z", "2026-07-20T12:30:00Z", "2026-07-20T12:30:05Z", "obs-2");

        MetricBaselineRow row = baselines.findById(scope.projectId, id).orElseThrow();
        assertEquals(200, row.currentCount());
        // Opened at the first sample's event time; a window reopening every page would never close on elapsed time.
        assertEquals("2026-07-20T10:00:00Z", row.currentOpenedAt());
        assertEquals("2026-07-20T12:30:00Z", row.lastEventAt());
        assertEquals("obs-2", row.countedThroughId(), "the guard advanced with the count it guards");
    }

    @Test
    @DisplayName("last_event_at widens by timestamp, not by string order")
    void lastEventAtIsComparedAsATimestampNotAsText() {
        Scope scope = scope("baseline-clock");
        String id = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a")).id();

        // Text columns, and Instant.toString() elides trailing zeros, so as TEXT '...:37Z' sorts after '...:37.400Z'
        // and freezes a thin bucket's clock. Compared as timestamptz, the later one wins.
        baselines.advanceWindow(id, 1, now(), null, "2026-07-20T10:00:37.400Z", null, null);
        baselines.advanceWindow(id, 1, now(), null, "2026-07-20T10:00:37Z", null, null);

        assertEquals(
                "2026-07-20T10:00:37.400Z",
                baselines.findById(scope.projectId, id).orElseThrow().lastEventAt(),
                "the later instant wins, whatever the two strings do alphabetically");
    }

    @Test
    @DisplayName("closing a window writes the control ring, opens an empty window, and leaves the watermark")
    void closeRotatesTheWindowAndKeepsTheWatermark() {
        Scope scope = scope("baseline-close");
        String id = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a")).id();
        baselines.advanceWindow(
                id, 500, now(), "2026-07-20T10:00:00Z", "2026-07-20T18:00:00Z", "2026-07-20T18:00:09Z", "obs-500");
        baselines.updateCurrentSketch(
                id, "{\"kind\":\"hist\",\"n\":500}", null, null, "[{\"t\":\"tr-in-window\"}]", now());

        baselines.closeWindow(id, CONTROL_RING, now());

        MetricBaselineRow row = baselines.findById(scope.projectId, id).orElseThrow();
        assertEquals(CONTROL_RING, row.controlJson(), "the closed window went into the control ring");
        assertNull(row.currentSketchJson());
        // Refs rotate with their sketch, or the new window opens holding a population it never measured.
        assertNull(row.currentRefsJson(), "the closed window's refs go with it");
        assertNull(row.currentOpenedAt(), "the next batch's earliest sample opens the new window");
        assertEquals(0, row.currentCount(), "current_count is per WINDOW, so it resets");
        // counted_through_* is per row: resetting it re-admits the closed window's tail, the double count it exists
        // to stop.
        assertEquals("obs-500", row.countedThroughId());
        assertEquals("2026-07-20T18:00:09Z", row.countedThroughAt());
    }

    /** One project and one signal row, the table's two foreign keys. */
    private record Scope(String projectId, String classifierId) {}

    /** The real {@code duration_drift} row, so the ON DELETE CASCADE is meaningful; its detector is never read. */
    private Scope scope(String slug) {
        String projectId = TenantFixture.bootstrap(tenants, slug).project().id();
        classifiers.seedBuiltIns(projectId);
        return new Scope(
                projectId,
                ClassifierRows.byKey(signals, projectId, BuiltInDetector.Kind.DURATION_DRIFT)
                        .orElseThrow()
                        .id());
    }

    /** A ring as MetricControl serializes one, opaque to this repository. */
    private static final String CONTROL_RING =
            "{\"kind\":\"control\",\"half_life_days\":7.0,\"days\":[{\"d\":\"2026-07-20\",\"m\":{\"kind\":\"hist\",\"n\":500}}]}";

    private static MetricBaselineRow seed(Scope scope, String measure, String bucketKey) {
        String now = now();
        return new MetricBaselineRow(
                Ids.ulid(),
                scope.projectId,
                scope.classifierId,
                measure,
                BucketKind.CALL_SITE,
                bucketKey,
                State.LEARNING,
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
                now);
    }

    private static String now() {
        return Instant.now().toString();
    }

    private long countIn(Scope scope) {
        return jdbc.sql("SELECT count(*) FROM metric_baseline WHERE project_id = :pid AND classifier_id = :sid")
                .param("pid", scope.projectId)
                .param("sid", scope.classifierId)
                .query(Long.class)
                .single();
    }
}
