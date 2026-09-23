// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierDtos.GroundednessStatusView;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessStatus;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.config.GroundednessProperties;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.EncoderFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The groundedness status against real rows: each of the row's states from the model's health, the
 * sweep job's cursor and {@code caught_up_at}, the mode and the row's switch, and a 422 for any other
 * classifier. The encoder is the loopback stub ({@link EncoderFixture}); the job rows are written
 * {@code done} so no background tick claims them mid-test.
 */
@SpringBootTest
class GroundednessStatusIntegrationTest {

    @Autowired
    GroundednessStatus status;

    @Autowired
    ClassifierRepository rows;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EncoderFixture encoder;

    @Autowired
    GroundednessProperties groundedness;

    @AfterEach
    void reset() {
        encoder.down();
        groundedness.setClassifierMode("dev");
    }

    @Test
    void neverSweptWithTheModelDownIsNotSetUp() {
        Fixture f = fixture("gs-not-set-up", true);

        GroundednessStatusView v = status.view(f.pid(), f.row());

        assertEquals("not_set_up", v.state());
        assertEquals("dev", v.mode());
        assertFalse(v.configured());
        assertFalse(v.available());
        assertFalse(v.everSwept());
        assertNull(v.lastScoredAt());
        assertNull(v.lastCaughtUpAt());
        assertEquals("main", v.setupRef(), "a build that is not a release links main");
    }

    @Test
    void devWithTheModelUpIsOn() {
        Fixture f = fixture("gs-dev-on", true);
        encoder.up();

        GroundednessStatusView v = status.view(f.pid(), f.row());

        assertEquals("on", v.state());
        assertTrue(v.configured());
        assertTrue(v.available());
        assertNotNull(v.checkedAt());
    }

    @Test
    void devSweptBeforeWithTheModelDownIsNotScoring() {
        Fixture f = fixture("gs-dev-not-scoring", true);
        sweptJob(f, null);

        GroundednessStatusView v = status.view(f.pid(), f.row());

        assertEquals("not_scoring", v.state());
        assertTrue(v.everSwept());
    }

    @Test
    void productionCaughtUpWithinTheMissedRunIsOnEvenWithTheModelAsleep() {
        Fixture f = fixture("gs-prod-on", true);
        groundedness.setClassifierMode("production");
        Instant caughtUp = Instant.now().minus(Duration.ofMinutes(50));
        sweptJob(f, caughtUp);

        GroundednessStatusView v = status.view(f.pid(), f.row());

        assertEquals("on", v.state());
        assertEquals("production", v.mode());
        assertFalse(v.available(), "asleep between runs is normal in production");
        assertEquals(caughtUp.toString(), v.lastCaughtUpAt());
    }

    @Test
    void productionWithNoCaughtUpSweepInTwoHoursIsNotScoring() {
        Fixture f = fixture("gs-prod-missed", true);
        groundedness.setClassifierMode("production");
        encoder.up();
        sweptJob(f, Instant.now().minus(Duration.ofHours(3)));

        GroundednessStatusView v = status.view(f.pid(), f.row());

        assertEquals("not_scoring", v.state(), "a missed run warns even while the model answers");
    }

    @Test
    void aDisabledRowIsOffWhateverTheModelDoes() {
        Fixture f = fixture("gs-off", false);
        encoder.up();
        sweptJob(f, Instant.now());

        assertEquals("off", status.view(f.pid(), f.row()).state());
    }

    @Test
    void anyOtherClassifierIs422() {
        Fixture f = fixture("gs-other", true);
        ClassifierRow other = rows.listByProject(f.pid()).stream()
                .filter(r -> BuiltInDetector.Kind.SECRET_LEAK.equals(r.detector()))
                .findFirst()
                .orElseThrow();

        TessaryException e = assertThrows(TessaryException.class, () -> status.view(f.pid(), other));
        assertEquals(ClassifierError.NOT_GROUNDEDNESS, e.error());
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private record Fixture(String pid, ClassifierRow row) {}

    private Fixture fixture(String name, boolean enabled) {
        String pid = TenantFixture.bootstrap(tenants, name).project().id();
        String now = Instant.now().toString();
        ClassifierRow row = new ClassifierRow(
                Ids.ulid(),
                pid,
                "groundedness-status",
                "Groundedness (status test)",
                null,
                BuiltInDetector.Kind.GROUNDEDNESS,
                null,
                false,
                1,
                enabled,
                ClassifierRow.Mode.TRACKING,
                now,
                now);
        rows.insert(row);
        return new Fixture(pid, row);
    }

    /** A sweep job whose cursor has moved, finished, and caught up at {@code caughtUpAt} if given. */
    private void sweptJob(Fixture f, @Nullable Instant caughtUpAt) {
        jobs.enqueue(f.pid(), f.row().id(), 1_800);
        jdbc.sql("""
            UPDATE job SET status = 'done', cursor_at = :cursorAt, cursor_id = 'obs-1',
                           payload = CASE WHEN CAST(:caughtUp AS text) IS NULL THEN payload
                                          ELSE payload || jsonb_build_object('caught_up_at', CAST(:caughtUp AS text)) END
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
            """)
                .param("cursorAt", Instant.now().minusSeconds(3_600).toString())
                .param("caughtUp", caughtUpAt == null ? null : caughtUpAt.toString())
                .param("pid", f.pid())
                .param("sid", f.row().id())
                .update();
    }
}
