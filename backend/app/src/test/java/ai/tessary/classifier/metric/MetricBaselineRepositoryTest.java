// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code metric_baseline} and its repository against the real Postgres, because the two things most
 * likely to be wrong here are both invisible to a mock.
 *
 * <p><b>The scope key is an expression, and expressions have to match by text.</b> Postgres infers the
 * arbiter for an {@code ON CONFLICT} from the expression it is given, so
 * {@link MetricBaselineRepository}'s {@code SCOPE_KEY} and {@code ux_metric_baseline_scope} must agree
 * character for character. A paraphrase compiles, passes review and then fails at runtime with "no
 * unique or exclusion constraint matching the ON CONFLICT specification" — on the very first sweep pass,
 * for every bucket.
 *
 * <p><b>NULL is a real scope.</b> An untagged environment is the common case, and NULL is distinct from
 * itself in a unique index: without the {@code COALESCE(environment_id, '')} the constraint would never
 * fire for those rows, so every sweep pass would insert another baseline and one bucket's traffic would
 * split across a growing pile of rows that each look perfectly stable.
 *
 * <p>The window mechanics get the same treatment for the same reason — {@code counted_through_*} is the
 * guard behaviour drift added after logging {@code trace_count} 565 for a project holding 443 distinct
 * traces, and a guard nothing exercises is a guard nobody knows is broken.
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
        // A second sweep pass over the same page mints a fresh id and asks again. It must get the row
        // that already exists — the sweep discovers buckets from traffic, so "insert or read" is the only
        // shape it ever needs and a duplicate-key failure here would be a perfectly ordinary event.
        MetricBaselineRow replayed = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a"));

        assertEquals(first.id(), replayed.id(), "the second ensure must return the FIRST row, not its own seed");
        assertEquals(1, countIn(scope), "one scope, one baseline");
        // DO UPDATE rather than DO NOTHING: the latter suppresses RETURNING on the conflicting row, which
        // would send the common path back for a second round trip.
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
        // The window's open time is the event time of its FIRST sample, so the second batch must not move
        // it — a window that re-opened on every page would never satisfy an elapsed-time close criterion.
        assertEquals("2026-07-20T10:00:00Z", row.currentOpenedAt());
        assertEquals("2026-07-20T12:30:00Z", row.lastEventAt());
        assertEquals("obs-2", row.countedThroughId(), "the guard advanced with the count it guards");
    }

    @Test
    @DisplayName("last_event_at widens by timestamp, not by string order")
    void lastEventAtIsComparedAsATimestampNotAsText() {
        Scope scope = scope("baseline-clock");
        String id = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a")).id();

        // These columns are text and Instant.toString() elides trailing zeros, so the stored forms are
        // variable-length. Compared as TEXT, '...:37Z' sorts after '...:37.400Z' under any collation that
        // ranks 'Z' above '.', and the earlier event would win — quietly freezing the clock a thin
        // bucket's window close depends on. Compared as timestamptz, the later one wins.
        baselines.advanceWindow(id, 1, now(), null, "2026-07-20T10:00:37.400Z", null, null);
        baselines.advanceWindow(id, 1, now(), null, "2026-07-20T10:00:37Z", null, null);

        assertEquals(
                "2026-07-20T10:00:37.400Z",
                baselines.findById(scope.projectId, id).orElseThrow().lastEventAt(),
                "the later instant wins, whatever the two strings do alphabetically");
    }

    @Test
    @DisplayName("closing a window writes the control ring, carries the straddling tail, and leaves the watermark")
    void closeRotatesTheWindowAndKeepsTheWatermark() {
        Scope scope = scope("baseline-close");
        String id = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a")).id();
        baselines.advanceWindow(
                id, 500, now(), "2026-07-20T10:00:00Z", "2026-07-20T18:00:00Z", "2026-07-20T18:00:09Z", "obs-500");
        baselines.updateCurrentSketch(id, "{\"kind\":\"hist\",\"n\":500}", null, null, now());

        // A batch legitimately straddles the cut: windows are cut on event time, and one ingest page can
        // hold samples from both sides. The caller splits it and hands the far side back, so those samples
        // open the next window instead of being counted into the closed one or dropped.
        baselines.closeWindow(
                id, CONTROL_RING, "2026-07-20T18:00:00Z", "{\"kind\":\"hist\",\"n\":12}", null, null, 12, now());

        MetricBaselineRow row = baselines.findById(scope.projectId, id).orElseThrow();
        assertEquals(CONTROL_RING, row.controlJson(), "the closed window went into the control ring");
        assertEquals("{\"kind\":\"hist\",\"n\":12}", row.currentSketchJson());
        assertEquals(12, row.currentCount(), "current_count is per WINDOW, so it resets to the carry");
        // counted_through_* is per ROW, not per window. Resetting it here would re-admit the tail of the
        // window just closed into the window just opened — the double count the watermark exists to stop.
        assertEquals("obs-500", row.countedThroughId());
        assertEquals("2026-07-20T18:00:09Z", row.countedThroughAt());
    }

    @Test
    @DisplayName("re-pinning moves only the deploy reference, and the state machine only the state")
    void repinAndStateAreSeparateWriters() {
        Scope scope = scope("baseline-repin");
        String id = baselines.ensure(seed(scope, Measure.TURN_DURATION, "cs-a")).id();
        baselines.advanceWindow(id, 400, now(), "2026-07-20T10:00:00Z", "2026-07-20T18:00:00Z", null, null);
        baselines.updateCurrentSketch(id, "{\"kind\":\"hist\",\"n\":400}", null, null, now());

        assertNull(baselines.findById(scope.projectId, id).orElseThrow().pinnedSketchJson());

        // The write behind "Legitimate — absorb". A human presses it: a triage verdict is read
        // never as authority to mutate the baseline, because an automatic re-pin would let the very next
        // window silently normalize a real regression.
        baselines.repin(id, "{\"kind\":\"hist\",\"n\":400}", null, null, null, "2026-07-21T09:00:00Z", "pv-123", now());
        baselines.updateState(id, State.ARMED, now());

        MetricBaselineRow row = baselines.findById(scope.projectId, id).orElseThrow();
        assertEquals("{\"kind\":\"hist\",\"n\":400}", row.pinnedSketchJson());
        assertEquals("pv-123", row.pinnedByVersionId());
        assertEquals(State.ARMED, row.state());
        assertEquals(400, row.currentCount(), "re-pinning reads the current window; it does not consume it");
        assertTrue(row.updatedAt().compareTo(row.createdAt()) >= 0);
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

    /** One project and one signal row to hang baselines off — the two foreign keys the table carries. */
    private record Scope(String projectId, String classifierId) {}

    /**
     * The real {@code duration_drift} signal row, which the catalog now carries. Nothing here reads the
     * signal's detector — the FK is the only thing under test, and pointing it at a real row is what
     * makes the ON DELETE CASCADE meaningful.
     */
    private Scope scope(String slug) {
        String projectId = TenantFixture.bootstrap(tenants, slug).project().id();
        classifiers.seedBuiltIns(projectId);
        return new Scope(
                projectId,
                signals.findByKey(projectId, BuiltInDetector.Kind.DURATION_DRIFT)
                        .orElseThrow()
                        .id());
    }

    /** A ring as MetricControl serializes one — opaque to this repository, which only has to store it. */
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
