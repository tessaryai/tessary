// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for the {@code media_ref} join table — which span's payload references which
 * {@code media_object}.
 *
 * <p><b>Why the table exists.</b> An externalized image is referenced by an {@code image_ref} node inside
 * the payload JSON and by nothing else: a string, invisible to foreign keys, joins and cascades. Media
 * therefore grew without bound and no cleanup could reclaim it, while "delete this project's data" left
 * every one of their images behind (#761). A row here is the reference made visible to the database, and
 * it is what {@code RetentionRepository.deleteOrphanedMedia} reads to decide that bytes are collectable.
 *
 * <p><b>Written in the payload's transaction, and keyed on the payload.</b> The FK is to
 * {@code span_payload} rather than to {@code span}, ON DELETE CASCADE, because media's lifetime IS the
 * payload's: retention ages payloads out ahead of spans by design, and the {@code image_ref} strings go
 * with them. A ref that outlived its payload would pin bytes nobody could ever name again.
 */
@Repository
public class MediaRefRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public MediaRefRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * File one span's media references. Idempotent on the full key, so an at-least-once redelivery — or
     * two spans of the same batch carrying the same deduplicated image — is a no-op rather than a
     * conflict. Must run after the payload row exists; the FK is the reason.
     */
    public void insertAll(String projectId, String traceId, String spanId, List<String> mediaIds) {
        if (mediaIds.isEmpty()) return;
        for (String mediaId : mediaIds) {
            jdbc.sql("""
                            INSERT INTO media_ref (project_id, media_id, trace_id, span_id)
                            VALUES (:pid, :mediaId, :traceId, :spanId)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("pid", projectId)
                    .param("mediaId", mediaId)
                    .param("traceId", traceId)
                    .param("spanId", spanId)
                    .update();
        }
    }

    /** One span's references, for {@link #insertAllForSpans}. */
    public record SpanMedia(String traceId, String spanId, List<String> mediaIds) {}

    /** Every reference of a whole batch in one JDBC batch (#984 M2); same key and idempotence as {@link #insertAll}. */
    public void insertAllForSpans(String projectId, List<SpanMedia> spans) {
        List<SqlParameterSource> rows = new ArrayList<>();
        for (SpanMedia span : spans) {
            for (String mediaId : span.mediaIds()) {
                rows.add(new MapSqlParameterSource()
                        .addValue("pid", projectId)
                        .addValue("mediaId", mediaId)
                        .addValue("traceId", span.traceId())
                        .addValue("spanId", span.spanId()));
            }
        }
        if (rows.isEmpty()) return;
        BatchCounts.requireReal(named.batchUpdate("""
                INSERT INTO media_ref (project_id, media_id, trace_id, span_id)
                VALUES (:pid, :mediaId, :traceId, :spanId)
                ON CONFLICT DO NOTHING
                """, rows.toArray(SqlParameterSource[]::new)));
    }
}
