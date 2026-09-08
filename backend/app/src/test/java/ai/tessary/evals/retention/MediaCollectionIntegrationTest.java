// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.retention;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Media garbage collection: bytes nothing references are reclaimed, and bytes something references are
 * not. Before this existed, {@code media_object} only ever grew — 549 MB of it on the corpus that
 * surfaced #761 — and "delete this project's data" left every image behind.
 *
 * <p><b>Why the sweep, and not just the statement.</b> This runs {@link RetentionSweeper#sweep()} because
 * the decision under test is not only the SQL: collection is deliberately OUTSIDE the policy classes, so
 * that a project which keeps its traces for ever still has its unreachable images collected. A test that
 * called the repository directly would pass while the sweeper skipped the project entirely — which is
 * exactly how this would break.
 *
 * <p>Every survival assertion has a twin that proves the pass reached this project at all.
 */
@SpringBootTest
class MediaCollectionIntegrationTest {

    /** {@link RetentionPinIntegrationTest}'s fingerprint: one context and one database for both, and
     *  sweeps confined to the database whose other tests already expect them. */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    private static final String OLD = Instant.now().minus(30, ChronoUnit.DAYS).toString();

    @Autowired
    RetentionSweeper sweeper;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("an image no payload references any more is collected; a referenced one is not")
    void collectsOrphans_keepsReferenced() {
        Project p = project("media-gc");
        payload(p, "trace-live", "span-live");
        String referenced = media(p, "referenced", OLD);
        String orphan = media(p, "orphan", OLD);
        ref(p, referenced, "trace-live", "span-live");

        sweeper.sweep();

        assertTrue(exists(p, referenced), "a payload still names these bytes — deleting them empties a page");
        assertFalse(exists(p, orphan), "and bytes nothing can name are not a retention decision, they are garbage");
    }

    @Test
    @DisplayName("freshly stored bytes are never collected, however unreferenced they look")
    void graceWindow_protectsInFlightBatches() {
        Project p = project("media-gc-grace");
        String justStored = media(p, "in-flight", Instant.now().toString());

        sweeper.sweep();

        assertTrue(
                exists(p, justStored),
                "bytes are stored during validation, before the transaction that files their media_ref"
                        + " rows — a collector with no grace window deletes an in-flight batch's images");
    }

    @Test
    @DisplayName("when the payload ages out, its images become collectable on the next pass")
    void payloadDeletion_releasesTheImage() {
        Project p = project("media-gc-release");
        payload(p, "trace-aging", "span-aging");
        String image = media(p, "aging", OLD);
        ref(p, image, "trace-aging", "span-aging");

        sweeper.sweep();
        assertTrue(exists(p, image), "still referenced");

        // The first tier of the traces class, which is what will delete this in production.
        jdbc.sql("DELETE FROM span_payload WHERE project_id = :pid AND trace_id = 'trace-aging'")
                .param("pid", p.id())
                .update();
        sweeper.sweep();

        assertFalse(
                exists(p, image),
                "the text that named the image is gone, so the image is unnameable — this is the whole"
                        + " reason retention deleting payloads first used to strand media for ever");
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    /** A trace, span and payload fresh enough that no policy tier touches them during the test. */
    private void payload(Project p, String traceId, String spanId) {
        String at = Instant.now().toString();
        jdbc.sql("INSERT INTO trace (project_id, id, started_at, event_ts)"
                        + " VALUES (:pid, :tid, :at::timestamptz, :at::timestamptz)")
                .param("pid", p.id())
                .param("tid", traceId)
                .param("at", at)
                .update();
        jdbc.sql("INSERT INTO span (project_id, trace_id, id, kind, started_at, event_ts)"
                        + " VALUES (:pid, :tid, :sid, 'llm', :at::timestamptz, :at::timestamptz)")
                .param("pid", p.id())
                .param("tid", traceId)
                .param("sid", spanId)
                .param("at", at)
                .update();
        jdbc.sql("INSERT INTO span_payload (project_id, trace_id, span_id, input, event_ts)"
                        + " VALUES (:pid, :tid, :sid, 'an image_ref lives in here', :at::timestamptz)")
                .param("pid", p.id())
                .param("tid", traceId)
                .param("sid", spanId)
                .param("at", at)
                .update();
    }

    private String media(Project p, String digest, String createdAt) {
        String id = Ids.ulid();
        jdbc.sql("""
                        INSERT INTO media_object (id, project_id, digest, media_type, bytes, size_bytes, created_at)
                        VALUES (:id, :pid, :digest, 'image/png', :bytes, 4, :createdAt)
                        """)
                .param("id", id)
                .param("pid", p.id())
                .param("digest", digest)
                .param("bytes", new byte[] {1, 2, 3, 4})
                .param("createdAt", createdAt)
                .update();
        return id;
    }

    private void ref(Project p, String mediaId, String traceId, String spanId) {
        jdbc.sql("INSERT INTO media_ref (project_id, media_id, trace_id, span_id)" + " VALUES (:pid, :mid, :tid, :sid)")
                .param("pid", p.id())
                .param("mid", mediaId)
                .param("tid", traceId)
                .param("sid", spanId)
                .update();
    }

    private boolean exists(Project p, String mediaId) {
        Long n = jdbc.sql("SELECT count(*) FROM media_object WHERE project_id = :pid AND id = :id")
                .param("pid", p.id())
                .param("id", mediaId)
                .query(Long.class)
                .single();
        return n != null && n == 1L;
    }
}
